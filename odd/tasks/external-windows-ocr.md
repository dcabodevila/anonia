# External Windows OCR

## Decision and scope
User explicitly accepted installing Tesseract separately instead of redistributing it inside the Windows installer. Prefer an explicit executable path through TESSERACT_COMMAND; PATH remains supported. Keep OCR entirely local. Preserve previous evidence and bundled-runtime compatibility. Do not install software, change persistent environment variables, delete archives, or generate an installer in this task. No new commit or push requested for this pivot.

Previous checkpoint: 6131ea9. Supersedes the unfinished private bundle assembly and redistribution investigation archived at `odd/archive/tasks/bundled-windows-ocr.md`; its findings are historical, not resolved. Its source downloads and audit dossier were removed during approved cleanup; the tracked original dossier remains recoverable from `6131ea9`.

## Plan
- [x] 1. Switch Windows packaging to JAR-only and update regression under strict TDD. Route: gentle-ai-worker, multi-file write trigger.
- [x] 2. Align root and Windows documentation and packaging skill with separate Tesseract installation. Same single writer; previous audit files preserved.
- [x] 3. Verify focused/regression checks and independent readback. Route: gentle-ai-verify after unavailable native review/assessment.
- [ ] 4. Native review remains unavailable; actual installer generation and external-OCR installed-app smoke test not performed in this scope.

## Verification
Strict TDD from AGENTS.md: observe intended assertion failure before implementation; then GREEN and regression. Commands: `mvn -o -Dtest=DesktopLauncherTest test`, `mvn -o test`. Documentation changes use structural readback. Tests do not prove installed app or clean-machine behavior. No JS or runtime Java behavior change planned.

## Boundaries
Allowed changes: packaging/windows/build-installer.ps1, packaging/windows/README.md, README.md, .agents/skills/windows-installer-packaging/SKILL.md, src/test/java/com/docanonymizer/adapter/web/DesktopLauncherTest.java. Runtime resolver stays unchanged: property > legacy private bundle > TESSERACT_COMMAND > PATH; new installers contain no private bundle.

Forecast: approximately 200-350 authored diff lines; no PR or delivery requested. Rollback boundary: these five files, preserving prior source and audit evidence.

## Evidence
Writer observed `mvn -o -Dtest=DesktopLauncherTest test` RED: intended new assertion rejecting OcrBundleRoot failed. GREEN: same command passed 5 tests. `mvn -o test` passed 134 tests. `git diff --check` passed. 326 authored changed lines, five files. No runtime Java or JavaScript changes.

Independent verifier reran focused Maven (5 passed), diff whitespace check (passed; CRLF warnings), and `powershell.exe -NoProfile -ExecutionPolicy Bypass -File packaging/windows/build-installer.ps1 -Plan` (passed). Readback confirmed only JAR staged, entrypoint retained, WiX PATH restoration unchanged, documentation distinguishes shell-local executable configuration from desktop shortcut environment. No actionable candidate defect found. No actual installer generation, installed-app smoke, or failure-path execution.

Native inspect ready after excluding untracked NUL; START failed candidate-view git add before mutation, no lineage created. ASSESS unavailable (empty native output); followed high-risk fallback with independent verifier. No native approval. At that verification point, NUL remained untouched and no root cause was established. It was subsequently removed by the explicitly authorized obsolete-audit cleanup; no native-review retry or causal confirmation followed. No commit/push for pivot. Previous bundle assembly work is superseded, not completed.
