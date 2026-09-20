# E2E-1 coverage report

## Observed result

The final run passed **11 of 11** Playwright cases against the packaged Java server: zero skipped, unexpected, or flaky results. Environment values recorded in `e2e/test-results/summary.json` were Node `v24.14.0`, OpenJDK `21.0.12.1`, and Chromium `153.0.8010.12`. Both synthetic OCR formats completed review and a verified download:

```json
{"status":"available","jpeg":"verified-download","png":"verified-download"}
```

| Journey | Browser assertion | Observed contract |
| --- | --- | --- |
| Server and static shell | Navigate to the UI; fetch health, JavaScript, and logo from page context | Real Java HTTP resources return expected bytes, content type, and no-store policy. |
| Tab and comparison ARIA | Click Result, use ArrowLeft, inspect `aria-selected`, visibility, and compare `aria-pressed` | Pointer and keyboard controls select the expected panels. |
| Search and type filters | Search for email, assert person exclusion, select `DNI`, then inspect every rendered chip | Search narrows visible entities and the type selector limits rows to the chosen type. |
| Occurrence navigation | Select a rendered multi-occurrence entity, require Next, and advance from occurrence 1 to 2 | Navigation is exercised rather than silently omitted for a guessed one-occurrence entity. |
| Manual cancel and entity edit | Escape a manual draft, edit email to a wider exact source span, apply, and download | Escape creates no entity; a valid edit persists through server review and verified export. |
| Manual save and reselect | Add `20240415`, deselect/reselect its checkbox, apply, and download | A saved manual code is selected again after reselecting and does not survive the export. |
| Verified normal download | Read downloaded Markdown after a successful review | Seeded name, DNI, and email are absent while labels and legitimate reference text remain. |
| Clipboard export | Grant Chromium clipboard permission, copy, then read clipboard bytes | Clipboard contains labels and excludes seeded email. This standalone case can skip only when permission grant is unavailable. |
| Blocked delivery and warning export | Reject a person group, require normal controls disabled, then save warning artifact | The warning file contains the displayed partial result and pseudonyms while ordinary delivery remains disabled. |
| Successful replacement | Deliver one PDF, upload a second valid PDF, then inspect reset state | A replacement clears prior Markdown and disables normal download until review completes again. |
| Invalid document boundaries | Upload unsupported text, empty PDF, and PDF-signature corrupt bytes | Each fails visibly without retaining a workspace; corrupt PDF reaches the PDF route rather than extension rejection. |
| JPEG OCR delivery | Generate a text JPEG through JDK ImageIO, upload, apply, and download | Local JPEG OCR detects synthetic identifiers and delivers anonymized Markdown. |
| PNG OCR delivery | Upload generated text PNG, apply review, and download Markdown | Local PNG OCR detects synthetic identifiers and delivers anonymized Markdown. |

No successful request is stubbed: the suite does not mock `fetch`, API responses, download creation, extraction, detection, review replay, anonymization, verification, clipboard data, or the Java process.

## Remaining limits

- Fixtures are synthetic and deterministic; this suite does not claim recall or safety for real documents.
- OCR remains environment-dependent. Only the exact unavailable-Tesseract message is classified as blocked/skipped; other OCR failures remain test failures.
- The product intentionally does not OCR scanned PDFs; image OCR coverage covers PNG and JPEG only.

## Assertion corrections based on observed behavior

- The blocked-result status, disabled normal controls, and enabled warning export are the delivery invariant. The API can still return a partial pseudonymized Markdown payload, so the suite does not require a literal blocked body or a leaked name.
- Warning-download assertions read the saved artifact. This implementation exports the displayed partial Markdown without the assumed generated metadata comment.
- `Juan Perez Lopez` renders as one occurrence in the browser projection. The suite instead selects a rendered multi-occurrence entity before asserting the Next control and counter transition.
