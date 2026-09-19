# Retire bundled OCR audit

User explicitly authorized cleanup, including deletion of approximately174 MiB ignored target/ocr-source-audit artifacts not recoverable from Git. Scope excludes all app/runtime/test files and other target outputs. Existing external-OCR edits and new installation guide must remain unchanged. No commit or push requested.

- [x] Remove packaging/windows/ocr-evidence/, target/ocr-source-audit/ and root accidental NUL.
- [x] Archive odd/tasks/bundled-windows-ocr.md to odd/archive/tasks/bundled-windows-ocr.md and update odd/tasks/external-windows-ocr.md reference.
- [x] Independently verify deletion boundaries, preserved working changes, and active references.

Evidence: writer measured172207544 logical bytes removed (not filesystem allocation);177 tracked dossier deletions. Six preserved current application/packaging/guide files matched writer before/after SHA256. Independent verification confirmed only177 evidence deletions plus old task removal, archive body preserved with historical header, roots absent, other target outputs and active files present, diff-check passed (CRLF warnings only). Corrected stale NUL reference by explicitly marking the earlier observation historical. Native assessment unavailable; independent structural verification performed. No tests required/run for documentation/evidence-only cleanup. No staging/commit. Archive and active task are ignored; future commit must explicitly include archive if history is to move rather than simply delete old tracked task.

Route: one delegated writer for filesystem cleanup, independent verifier. Strict TDD not applicable: removal of unused historical evidence and documentation only, no runtime behavior. Structural/file inventory checks required; no artificial failing tests. Tracked dossier is recoverable from6131ea9; ignored downloads have no Git backup. Archived document must clearly mark historic paths/results as no longer current. No unrelated cleanup.
