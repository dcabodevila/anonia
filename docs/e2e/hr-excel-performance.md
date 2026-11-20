# HR Excel performance: freeze diagnosis

Owner report: with an HR Excel of about 300 rows, full of IDs and person names, the app "freezes
for a while". This document records how to reproduce it, the measurements, the root causes and a
proposed fix. **The fix is not implemented**; it waits for the owner's approval.

## Reproduce

```bash
mvn -o -DskipTests package
# 1. Synthetic HR workbooks (roster + payroll + absences; all data generated from a fixed seed)
java -Dfile.encoding=UTF-8 -cp target/doc-anonymizer.jar e2e/perf/HrWorkbookGenerator.java e2e/.runtime/hr-fixtures
#    optional sizes: ... e2e/.runtime/hr-fixtures 50 300   (default 50 150 300 600 1000 3000)

# 2. Per-stage server profile (wall time + allocated bytes per stage)
mvn -o test-compile
java -Xmx4g -Dhr.sizes=50,150,300 -cp "target/doc-anonymizer.jar;target/test-classes" \
  com.docanonymizer.domain.service.HrExcelStageProfiler e2e/.runtime/hr-fixtures e2e/.runtime/hr-stage-profile.json

# 3. Browser journey + CLI (opt-in, slow; generates its own fixtures)
npm --prefix e2e install && npm --prefix e2e exec playwright install chromium
HR_SIZES=50,150,300 npm --prefix e2e run test:perf
```

Use `:` instead of `;` as class path separator outside Windows. Results land in
`e2e/test-results-perf/` (`web-<rows>.json`, `cli.json`). The Playwright suite fails on purpose
while a click blocks the main thread more than 200 ms or a single task lasts more than 1 s: that
failure is the freeze.

Fixtures per employee: Spanish given name + two surnames, DNI/NIE with valid control letter, NSS
with valid control digits, IBAN with valid CCC and ISO check digits, email, phone, address,
employee ID, department, salary, dates, and the manager's full name. Each employee appears in
the roster and payroll sheets; one in five also in absences.

## Measurements

Windows 11, JDK 21, Chromium headless 1243 (Playwright 1.63.0). Single runs: treat them as an
order of magnitude, not a benchmark.

### Server stages (`HrExcelStageProfiler`)

| Employees | Text chars | Candidates | Detectors | Propagation + overlaps | `ExactOccurrences.expand` (analyze) | `ReviewEdits.replay` (apply) | Verification |
|---|---|---|---|---|---|---|---|
| 50 | 21 K | 978 | 0.03 s | 0.06 s | 0.4 s / 1.9 GB alloc | 0.6 s / 3.2 GB | 0.08 s |
| 150 | 63 K | 2,907 | 0.07 s | 0.5 s | 3.1 s / 16 GB | 5.1 s / 26 GB | 0.7 s |
| 300 | 127 K | 5,789 | 0.14 s | 2.4 s | 12–17 s / 64 GB | 21–29 s / 108 GB | 2.9 s |
| 600 | 254 K | 11,608 | 0.3 s | 13 s | 68 s / 263 GB | 113 s / 440 GB | 13 s |
| 1000 | 424 K | 19,326 | 0.5 s | 52 s | 205 s / 721 GB | 344 s / 1.2 TB | 35 s |

Direct `POST /api/analyze` of the 300-employee workbook with curl: 16.4 s, 1.5 MB JSON.

### Browser journey (`test:perf`)

| Employees | Analyze request | Main-thread block after analysis | One mark/dismiss click | One click on a document mark | «Anonimizar» (request / longest block) | Copy / download |
|---|---|---|---|---|---|---|
| 50 | 1.1 s | 1.7 s | 1.7–2.0 s | 1.7 s | 0.8 s / 2.6 s | 0.08 s / 0.13 s |
| 150 | 3.9 s | 22 s | **25–30 s** | **26–28 s** | 5.9 s / 47 s | 0.08 s / 0.15 s |
| 300 | 16 s (curl) | page unresponsive > 300 s (test timeout) | not reached | not reached | not reached | not reached |

DOM size at 150 employees: 2,750 document marks, 1,462 entity rows, 35,136 elements.

CPU profile of one checkbox click at 150 employees (27.5 s): **26.9 s self time in
`getPropertyValue`**; everything else in `app.js` together is under 0.6 s.

### CLI (`Main --dry-run`)

| Employees | Pipeline time | Exit |
|---|---|---|
| 50 | 0.8 s | 2 (verification blocked) |
| 150 | 2.1 s | 2 |
| 300 | 5.7 s | 2 |

## Verdict: reproduced, two independent causes

### 1. Front-end, the main freeze: forced style recalculation per mark

`src/main/resources/web/app.js:999-1002`:

```js
function colorOf(type) {
  const value = getComputedStyle(document.documentElement).getPropertyValue(key);
```

`makeHighlight` (`app.js:898-901`) calls `colorOf` for every mark while `renderDocument`
(`app.js:934`) is appending marks into a freshly cleared container. Each call reads computed
style on a dirty DOM, so the browser recalculates styles for the whole document every time:
cost ≈ marks × DOM size, i.e. quadratic in rows. Every interaction (`toggleEntity` →
`refreshReview`, `app.js:833`; clicking a mark → `refreshLocation`) re-renders the entire document
and entity list, so every click pays it again. This is the "se queda congelada a ratos" the
owner sees: 2 s per click at 50 employees, ~27 s at 150, minutes at 300.

Secondary front-end cost (small next to the above): `occurrenceIds` (`app.js:248-262`) runs
`state.detections.find` inside a `sort` comparator for every entity row, and every click
rebuilds all rows.

### 2. Server, the long spinner: `ExactOccurrences` re-normalizes the whole text per candidate

`src/main/java/com/docanonymizer/adapter/web/ExactOccurrences.java:30-33` calls
`matches(text, value)` for each candidate, and `matches` (l.87-88) calls
`normalizeSource(source)` on the **entire document** each time, building a `String` per
character (l.117) and a boxed `List<Integer>` padded with `null` (l.130-132). Cost is
candidates × text length: 64 GB allocated for 300 employees. «Anonimizar» repeats it:
`ReviewEdits` constructor (`ReviewEdits.java:40`) calls `expand` again on the already expanded
analysis (67 % more candidates). `groups.containsValue` (`ExactOccurrences.java:60`,
`ReviewEdits.java:50`) adds a smaller O(n²). The CLI does not use these classes, which is why
the same file takes 5.7 s there.

Above ~600 employees `EntityPropagator` (1.5 M propagated spans at 1000) feeding the O(n²)
`DetectionEngine.resolveOverlaps` (`DetectionEngine.java:50`) and `RedactionVerifier` also grow
past 10 s; they affect the CLI too.

### Side finding (correctness, not performance)

The CLI blocks every HR workbook: `[C1-VALOR-LITERAL] IBAN sigue presente literalmente en la
salida`, about one per employee (50 / 149 / 298). The web path passes verification because
`ExactOccurrences` adds the missing exact occurrences. The cause was not investigated; it looks
like the second occurrence of each IBAN (roster and payroll sheets) is not detected or not
propagated in the pipeline used by the CLI.

## Proposed fix (not implemented)

1. **Front-end colors (largest win).** Read the type colors once — a `Map` filled from a single
   `getComputedStyle` at startup or on theme change — or set the color through a CSS class /
   `data-type` attribute instead of inline style. Expected: a click at 150 employees drops from
   ~27 s to under ~0.5 s (the remaining full re-render cost).
2. **Front-end re-render.** In `occurrenceIds`, index detections by id once per render (`Map`)
   instead of `find` inside `sort`. Later, optionally, toggle only the affected rows and marks
   instead of rebuilding everything, and build the document in a `DocumentFragment`.
3. **Server `ExactOccurrences`.** Normalize the text once per `expand` with an `int[]` offset map;
   search once per distinct normalized value (cache results); replace `containsValue` with a
   `Set` of used keys. Make `ReviewEdits` skip re-expansion when the stored analysis is already
   expanded (or store a flag). Expected: analyze and apply under ~1 s at 300 employees, and the
   GC pressure disappears.
4. **Later (≥ 600 employees).** Sweep-line `resolveOverlaps` (sort by start, check only
   neighbours/interval tree) and cap or deduplicate single-token propagation; batch
   `RedactionVerifier` searches over one folded copy of the Markdown.

**Risks.** (1) theme switching must refresh the cached colors; (3) `ExactOccurrences` and
`ReviewEdits` define which occurrences are offered and substituted, so any change must keep the
exact same candidate list and IDs, otherwise reviews already recorded against IDs (`review-v1`
edit log) would break and values could leak; (4) changes overlap arbitration semantics and needs
the security corpus.

**Test plan (Strict TDD).** RED first for each step:
- JS: a `node --test` unit test that renders N detections and asserts `getComputedStyle` is
  called at most once per render (spy), and an `occurrenceIds` ordering test.
- Java: characterization test that `ExactOccurrences.expand` and `ReviewEdits.replay` return
  identical detections (ids, ranges, entity keys) before and after, over the generated HR
  workbooks and the existing corpora; plus a size test (e.g. 300 employees under a time budget).
- E2E: `npm --prefix e2e run test:perf` must pass its 200 ms click / 1 s long-task budgets at
  50/150/300; the default `npm --prefix e2e test` and `mvn -o test` must stay green.

## Not verified

- No fix was implemented or measured; the expected gains are estimates.
- Browser journey at 300+ employees never became interactive within 300 s, so its click and
  apply numbers are missing; server timings for 300–1000 come from the profiler and curl.
- `.xls` fixtures are generated but were not timed; only `.xlsx` was measured.
- Single runs on one Windows machine; no repetition or variance analysis; no heap limit tuning of
  the web server.
- The CLI IBAN verification block was observed but its root cause was not investigated.
