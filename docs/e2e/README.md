# Browser E2E suite

This suite drives Chromium through the shipped browser UI and a separately started Java `WebServer`. Successful anonymization requests are never intercepted or mocked. Each run creates an isolated local port, a temporary synthetic PDF and PNG, then stops the Java process during Playwright teardown.

## Prerequisites

- Java 17 and Maven, with dependencies already available to Maven offline mode.
- Node.js and npm.
- Chromium installed only for this local Playwright project.
- Optional: local Tesseract with the Spanish `spa` model for the image journey.

## Run

From the repository root:

```bash
mvn -o -DskipTests package
npm --prefix e2e install
npm --prefix e2e exec playwright install chromium
npm --prefix e2e test
```

The package step deliberately skips tests: it only builds the runnable JAR used by the E2E server. `npm --prefix e2e test` starts `com.docanonymizer.adapter.web.WebServer` from that JAR, waits for `/health`, runs headless Chromium, and terminates the owned process afterwards. It does not modify production source or use a shared application port.

## Evidence and cleanup

Runtime files live under `e2e/.runtime/` and are ignored. They include generated synthetic fixtures, the dynamically selected port, downloaded Markdown, and `server.log`. Playwright writes machine-readable output under `e2e/test-results/` and its HTML report under `e2e/playwright-report/`; both are ignored:

- `playwright.json` — per-test machine-readable browser result.
- `summary.json` — command result, aggregate totals, Node/Java/Chromium versions, OCR state, and artifact locations.
- `../playwright-report/index.html` — local failure screenshots and traces when applicable.

The global setup owns both the synthetic fixture process and the Java server. On startup failure it stops the child before reporting the failure; normal and failed browser runs invoke the same teardown. If a test runner itself is forcibly killed, use the PID/port from `.runtime/server.json` and inspect `.runtime/server.log` before manually stopping the remaining process.

## OCR limitation semantics

The OCR cases upload generated PNG and JPEG fixtures containing no real data. The JPEG fixture uses JDK ImageIO at test setup, so it adds no production or npm dependency. A usable installation must complete analysis, apply review, and download anonymized Markdown for both formats; recognizing text alone is insufficient. The suite records a blocked/skipped OCR result only for the exact product message proving its Tesseract command is unavailable:

```text
OCR local no disponible. Instala Tesseract y el modelo espanol (spa).
```

Any other OCR error, including an unreadable image, absent language model, or insufficient extracted text, is a failing result with its observed message in `.runtime/ocr-status.json`. JPEG is separately checked to ensure it reaches the OCR route rather than the unsupported-type gate.

## Dependency maintenance

`@playwright/test` is pinned to 1.63.0. The prior 1.52.0 audit reported two high-severity findings through `playwright`; updating to the non-major patched version resolved them. Verify the installed lockfile with:

```bash
npm --prefix e2e audit --json
```
