# Windows OCR source evidence

This evidence set records the observed 34-file native OCR closure and Spanish model without creating a bundle, manifest, or redistribution notice. It is **partial**: all 30 mapped MSYS2 DLLs have exact package-member matches (libssh2 via wincng), but eight original package BUILDINFO recipe hashes differ from the archived recipe snapshot and GCC provenance remains unresolved.

## Quick review path

1. Read `audit.json` for the machine-readable file hashes, component mapping, and status of every native file plus `spa.traineddata`.
2. Read `licenses/` for the actual upstream license texts collected so far.
3. Inspect `target/ocr-source-audit/archives/collection.json` for every candidate source archive, its declared checksum result, and extracted member list.
4. Read `OBLIGATIONS_READINESS.md` for the per-component evidence gap before any distribution decision.
5. Read `PATCH_PROVENANCE.md` for exact downstream patch checksum and historical-attempt evidence.

## What is evidenced

| Subject | Evidence | Status |
|---|---|---|
| Local original installer | SHA-256 `bee9e3434bd94fd65387d9be28cd467a41f61b1275383b55b0f59a1331270ae4`, 26,573,224 bytes | Parent verified against the official 5.5.3 release API; not copied here |
| Native closure | SHA-256 and byte count for `tesseract.exe` plus 33 DLLs | All 34 listed files are accounted for |
| Spanish model | SHA-256 `6f2e04d02774a18f01bed44b1111f2cd7f3ba7ac9dc4373cd3f898a40ea6b464` | Parent verified against the `tessdata_fast` Git blob |
| Tesseract binary pair | Official installer members and installed `tesseract.exe`/`libtesseract-5.dll` | Exact bytes; source tag/build association recorded, reproducible build unproven |
| Tesseract and model licenses | Official upstream `LICENSE` texts | Archived |
| JBIG-KIT | Verified 2.1 source archive, five MSYS2 patches, and extracted `COPYING` | Archived; local DLL binary match remains unresolved |
| 30 MSYS2 DLLs | Recipe-derived package archives with matching `.PKGINFO` version/release and exact DLL-member SHA-256 | Candidate package-member byte correspondence observed; not packaging approval |
| libssh2 | OpenSSL candidate retained as mismatch; wincng candidate metadata/member exact | Local DLL corresponds to recipe-defined wincng package, not the rejected OpenSSL package |
| winpthreads | Exact member match, commit-addressed upstream `COPYING`, patch, and GitHub commit snapshot | Snapshot archive SHA-256 is observed, not asserted upstream-proven |
| GCC runtime | Official installer members match installed files; two approved Ubuntu Noble packages analyzed | All four candidates differ in `.text`; base-package copyright and exact compiler provenance remain unresolved |
| Downstream patches | 39 pinned recipe patches with verified recipe checksums | All archived; historical libarchive raw-URL 404 retained separately |
| MSYS2 BUILDINFO recipes | `pkgbuild_sha256sum` compared to archived pinned PKGBUILD | 19 exact recipe hashes; eight mismatches remain candidate source/patch associations |

## Important boundaries

- `audit.json` is not `bundle-manifest.json` and grants no packaging approval.
- The historical MSYS2 snapshot is not proof that any local DLL came from those recipes.
- `libgcc_s_seh-1.dll` and `libstdc++-6.dll` are deliberately not attributed to MSYS2 GCC: the UB Mannheim recipe links them from a separate Ubuntu/Debian cross-compiler location.
- This inventory does not provide legal advice, determine compliance, or establish the application license.

## Required follow-up

1. Retain/review the rejected libssh2 OpenSSL candidate alongside the exact wincng match; do not collapse their differing crypto variants.
2. Resolve the eight package BUILDINFO-to-archived-recipe hash mismatches before treating their source/patch set as exact build provenance.
3. Establish a provenance path from the observed winpthreads commit snapshot to the package build if release-grade source correspondence is required, and resolve GCC base-package/compiler provenance without inferring Runtime Library Exception applicability.
