# Offline person-name dictionaries

`ResourceGazetteer` combines the compatibility list `nombres-es.txt` with
`nombres-intl.txt.gz`. The generated resource contains **116,031 canonical-unique
names**; the default Java gazetteer contains **116,032** including legacy `Mª`.
Runtime loading is entirely offline. The default gazetteer also loads the INE
surname dictionary `apellidos.txt.gz` and packaged exclusions; see **Surnames (T5)**
below. `size()` remains the given-name count for compatibility.

## Regenerate

From the repository root, with Python 3.10+ and `curl` on PATH:

```sh
python scripts/gazetteer/build_given_names.py
mvn -o -Dtest=ResourceGazetteerTest,ForeignNameCorpusEndToEndTest test
mvn -o test
```

The script downloads INE and Wikidata using `curl` with certificate verification
and the generic User-Agent `doc-anonymizer-gazetteer-build/1.0 (offline dictionary build)`.
It keeps downloads in memory and writes only the gzip resource and the small
`scripts/gazetteer/build-report.json` provenance report. Failed downloads or a
union of 100,000 names or fewer abort before replacing existing outputs.
Both builders close and flush a same-directory temporary file, then use
`os.replace`; resources and reports are not truncated in place. A failed replace
may leave a `.tmp` file for diagnosis. Each file replacement is atomic, not the
whole multi-file build.
Regeneration can take tens of minutes; endpoint errors are retried. The report
records source hashes, retrieval time, class counts and actual excluded words.

### Optional SSA input

SSA's CDN returned HTTP 403 to automated clients in this environment. **SSA was
not included in this build.** Download the official archive in a browser:

<https://www.ssa.gov/oact/babynames/names.zip>

Then regenerate with the downloaded public-data archive (do not commit it):

```sh
python scripts/gazetteer/build_given_names.py --ssa-zip path/to/names.zip
```

The script sums counts across all years and both sexes, retaining names with at
least **10 total occurrences**. `--ssa-min-total` can raise that threshold; values
below 5 are rejected. Without `--ssa-zip`, the skip is explicit in stdout and the
provenance report. The optional merge implementation has not been exercised with
a real SSA archive in this run.

## Sources and rights

Retrieved **2026-10-02**. These are derived name spellings, not population
statistics. Normalization and exclusions are our processing, not source claims.
No source endorses this application.

| Source | Official data and coverage | License / terms |
|---|---|---|
| INE, Spain | [Residents' names by mean age, XLSX](https://www.ine.es/daco/daco42/nombyapel/nombres_por_edad_media.xlsx), both male/female sheets; names with national frequency ≥20. Annual population census reference date **2025-01-01**, published **2026-04-23** per the [INE operation page](https://www.ine.es/dyngs/INEbase/operacion.htm?c=Estadistica_C&cid=1254736177009&menu=ultiDatos&idp=1254735572981). | [INE reuse notice](https://www.ine.es/ss/Satellite?c=Page&cid=1254735849170&pagename=INE%2FINELayout&L=0): permits commercial/noncommercial reuse of INE-origin information, with source attribution, update date where supplied, no distortion of meaning or implied endorsement; reuse at the reuser's risk. The footer links CC BY 4.0 but its tooltip says BY-SA; this document relies on the explicit reuse notice rather than resolving that inconsistent footer. |
| Wikidata | [SPARQL endpoint](https://query.wikidata.org/sparql); items whose `P31` is Q202444 (given name) or any transitive `P279` subclass, including male Q12308941, female Q11879590 and unisex classes. Labels are restricted to the Latin-script language allowlist in the script and individually checked for Latin letters. | Structured data: **[CC0](https://www.wikidata.org/wiki/Wikidata:Licensing)**. Only structured labels and class relations are used, not wiki prose. |
| SSA, US (optional; skipped) | [Official national baby-name archive](https://www.ssa.gov/oact/babynames/names.zip), Social Security card applications from 1880 onward. | **CC0 / public domain**, as recorded in the official [Data.gov catalog](https://catalog.data.gov/dataset/baby-names-from-social-security-card-applications-national-data). The catalog links the same SSA ZIP, not a separate working mirror. |

INE attribution for processed data:
**Elaboración propia con datos extraídos del sitio web del INE: www.ine.es**.

## Build counts

Counts are spellings, not people. Wikidata raw labels include repeated spellings
across languages and classes. Counts below are after canonical deduplication.

| Source | Raw entries/labels | Normalized unique | Excluded | Accepted | Added to union |
|---|---:|---:|---:|---:|---:|
| INE | 63,440 | 20,837 | 14 | 20,823 | 20,823 |
| Wikidata | 3,174,491 | 108,897 | 34 | 108,863 | 95,208 |
| SSA | skipped | — | — | — | — |

Final gzip: **116,031 lines, 368,159 bytes**. Sorted output, a deterministic
representative for each canonical key, and a zero gzip timestamp make identical
inputs byte-stable. Live Wikidata is not a frozen snapshot: regeneration can
change counts, and edits during paginated retrieval can shift page boundaries.
The report identifies this particular build.

## Normalization and exclusions

- Strip surrounding whitespace and normalize to NFC.
- Accept Latin-letter names with internal hyphens or straight/curly apostrophes;
  reject spaces, digits, other punctuation and single-letter entries. Combining
  accents that remain after NFC are preserved in spellings.
- Deduplicate using NFD, dropping nonspacing marks and punctuation, then
  lowercasing, matching Java's comparison for these entries.
- Apply `scripts/gazetteer/exclusions.txt` using the same canonical key.

The **163 exclusion keys** cover Spanish/Catalan/Galician function words and
particles, legal/administrative/address headings, and clear document nouns and
calendar words. Actual removed entries include `Real`, `Sala`, `General`, `Caja`,
`Ley`, `Firma`, `de`, `del`, `la` and `el`; see the report for all source matches.
Ambiguous given names **Rosa, Paz, Grace, Victoria, Mercedes and Pilar** remain.
Calendar words with established person-name use (`Julio`, `Domingo`, `Abril`,
`Mayo`) also remain. No names were manually added to the international resource.

All existing negative controls passed without additional control-driven
exclusions or weakened assertions. T4 and the redaction-only former T6 corpus
group were enabled in T4; T5 is now also enabled.

## Runtime cost and verification

The default gazetteer is a lazily initialized immutable `HashSet` shared across
pipelines; custom resource-path construction still loads only that resource and
supports both plain UTF-8 and gzip. Custom resource construction still loads only
that given-name resource (no surnames/exclusions). `PipelineFactory`'s user-name
union now forwards surname and exclusion lookups to the default gazetteer.

The focused test measured an **uncached gzip load in a warmed JVM: 30.5 ms**,
**25,165,824-byte heap delta during loading**, and approximately **10,485,640 bytes
retained after explicit GC**. These are local, GC-sensitive estimates, not a
performance guarantee. Repeated default construction reuses the shared set.

Historical T4 TDD evidence:

- Initial focused RED: 56 tests, 20 behavioral failures, no errors, one skipped
  group (T5); all seven T4 names and Paz/Grace leaked.
- First GREEN: 56 tests, no failures/errors, one skipped group.
- Canonical-uniqueness regression RED: 58 tests, one intended failure
  (`Abdalaziz` duplicated after Java removes internal punctuation).
- After aligning the generator's canonical key and regenerating: 58 tests,
  no failures/errors, one skipped group.
- Final focused run, including additional ambiguity/exclusion controls: 73 tests,
  no failures/errors, one skipped group.
- Final `mvn -o test`: 317 tests, no failures/errors, one skipped group (T5).
  `SecurityCorpusEndToEndTest` (31), `DetectionClassificationTest` (11),
  `PipelineFactoryTest` (7) and all seven foreign-corpus negative controls passed.

The exact T4 focused command is shown above. No confidence lowering or detector
changes were made in T4.

## Surnames (T5)

Regenerate independently of the much slower given-name build:

```sh
python -B scripts/gazetteer/build_surnames.py
mvn -o -Dtest=GazetteerPersonDetectorTest,ResourceGazetteerTest,ForeignNameCorpusEndToEndTest test
mvn -o test
```

### Official source and rights

INE's [results page](https://www.ine.es/dyngs/INEbase/operacion.htm?c=Estadistica_C&cid=1254736177009&menu=resultados&idp=1254735572981)
links the [complete resident-surname frequency table](https://www.ine.es/daco/daco42/nombyapel/apellidos_frecuencia.xls).
Unlike its given-name table, this is **BIFF8 XLS, not XLSX**; the corresponding
`.xlsx` URL returned 404. Retrieved **2026-10-02**, census reference date
**2025-01-01**, published **2026-04-23** per the operation page. The same INE reuse
notice and attribution above apply; spellings are our processed derivation, not
an INE-endorsed dictionary.

The workbook has two sheets, selected by **first-surname frequency**: ≥100 and
20–99. Both include first- and second-surname frequency columns for the same
spellings. This is not an independently exhaustive list of second-only surnames
with first-surname frequency below 20. Second-surname counts ≥20 are available
for 54,744 rows. Suppressed counts (`..`) are not interpreted as frequencies.
Kowalski is present (174 first, 44 second). García/Pérez/López/Ruiz are present;
comparison ignores accents and case.

`ine_xls.py` is a narrow read-only stdlib parser for this workbook: OLE regular
Workbook streams, BIFF8 shared strings (including continuations), numeric cells,
and **cached numeric formula results only**. It does not evaluate formulas,
macros or external links. Layout checks require both frequency sheets and their
column headers; coverage/reference-surname guards abort before writes. Downloads
use the existing verified-TLS curl helper and generic User-Agent, without email
addresses or personal data in requests. No new dependency or runtime network is
needed.

### Build counts and normalization

| Stage | Count |
|---|---:|
| ≥100 first-surname sheet | 27,661 |
| 20–99 first-surname sheet | 58,851 |
| Raw spellings | 86,512 |
| Normalized canonical-unique words | 76,568 |
| Excluded words | 56 |
| Final surname words | **76,512** |

Final gzip: **210,694 bytes**, SHA-256
`7d91582cc54704f06db6844c67545e5a5cc89cdbe246cd95119f4cce4e324c0a`.
Source SHA-256:
`93e894da8e33332a11725bf07295db5607d64e2d40d3a8d3607fcd42f8f2cf28`.
Full metadata is in `scripts/gazetteer/surname-build-report.json`.

The builder reuses the given-name normalizer: Latin words with internal
hyphens/apostrophes, canonical deduplication, sorted output and gzip `mtime=0`.
Multiword surname entries are rejected, not split into potentially noisy words.
The unchanged 163-key exclusion list removes 56 surname words and is copied
byte-for-byte to `/gazetteer/exclusions.txt` for offline runtime lookup.
Both surname and given-name resource writes now use the shared atomic writer;
the given-name resource was not otherwise regenerated in T5.

### Detector contract and controls

After the existing postal-code guard and `NameSpanRefiner`, a capitalized
multiword candidate is accepted when its first word is a given name (unchanged,
confidence **0.85**), or a later non-particle, non-excluded word is a surname and
the first word is not excluded (confidence **0.80**). Only the refined span may
supply the surname signal. Confidence is display-only. Existing custom given
names retain their previous path; the factory wrapper forwards surname and
exclusion signals. Default port methods return false to preserve existing stubs.

All seven original corpus controls still require literal content survival.
Four additional **non-person** controls require no overlapping PERSON detection
(both per-detector and in pipeline output), permitting other valid entity types:
Banco Santander, Avenida de Castilla, Hospital Clínico San Carlos, Comunidad de
Madrid. No control or seeded name was dropped. Baseline diagnostics established
that Banco Santander is removed by `literal-organization`
(`LiteralOrganizationDetector`, ORGANIZATION, REGEX, 0.95), supplied by the
current user-rule union rather than the hardcoded Banco Pastor/Banco Popular
list. Avenida de Castilla is ADDRESS from `direccion` (`AddressDetector`, 0.75).
No user-rule file was read or modified.

T5's two corpus names already passed through T4 given names. The intended RED
was the stub surname-only unit test and resource/exclusion tests, not those
corpus cases. With type-aware controls approved, the focused command above ran
100 tests with **15 behavioral failures, no errors/skips**. After implementation,
the same command passed **100/100**; the first full suite passed **339/339**.
The initial untyped-control run had 17 failures, including the two expected
non-PERSON redactions; these were corrected by explicit user approval, not by
weakening existing survival assertions.

After refactoring the particle matcher into a shared compiled pattern and adding
refined-span, exclusion-copy parity and factory-union checks, the final focused
run passed **101/101** and `mvn -o test` passed **341/341**, with zero failures,
errors or skipped tests. This includes SecurityCorpusEndToEndTest (31),
DetectionClassificationTest (11), PipelineFactoryTest (8), all original controls
and all four type-aware controls. A second surname build produced the identical
source hash, dictionary count, gzip size and gzip hash above. Atomic writes were
exercised by both successful surname builds; interruption/failure fault injection
and a full given-name rebuild were not run.
