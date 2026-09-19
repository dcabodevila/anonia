# Project development instructions

## Strict TDD

Strict TDD is required for new behavior changes and bug fixes in this project, including delegated work. Source: explicit user decision. This applies to ODD and does not select SDD/OpenSpec.

1. RED: add a focused regression or behavior test first, run it, and observe failure for the intended missing behavior. Compilation errors or environment failures are not valid RED evidence.
2. GREEN: implement the smallest change that makes the test pass, then rerun it.
3. REFACTOR: improve structure without changing behavior, rerun focused tests, and run applicable regression suites.
4. Record the exact commands and observed RED/GREEN/refactor outcomes. Never infer or fabricate historical TDD evidence.

### Test runners

- Java focused test: `mvn -o -Dtest=<TestClass> test` (replace `<TestClass>` with the actual affected class).
- Java regression suite: `mvn -o test`.
- JavaScript focused test: `node --test src/test/js/<name>.test.js` (replace `<name>` with the actual test filename).
- JavaScript regression suite: `node --test src/test/js/*.test.js`.
- JavaScript syntax check: `node --check src/main/resources/web/app.js`; this supplements tests and never substitutes for RED evidence.

For cross-boundary changes, test both Java and JavaScript behavior. Forward this policy and the exact applicable commands to delegated writers. If meaningful automated testing is unavailable, report the limitation and obtain an explicit exception before implementation. Documentation-only changes need structural readback, not artificial failing tests.

Changes implemented before this policy was requested retain their actual verification history; do not relabel regression-only work as TDD or revert working code merely to manufacture RED evidence.

## Packaging for manual app testing

When packaging and launching the application for manual testing, use `mvn -o -DskipTests package`. Do not rerun automated suites merely to start or restart the app. Source: explicit user preference.

This startup-only rule does not disable Strict TDD or the required automated verification for implementation changes. Report skipped startup tests accurately; packaging success is not test evidence.

## Project skills

- `.agents/skills/windows-installer-packaging/SKILL.md` — Trigger: empaqueta la aplicación, empaqueta la versión, generar instalador Windows.
