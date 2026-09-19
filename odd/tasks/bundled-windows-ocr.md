# Bundle private Windows OCR

Authorized: include Tesseract, Spanish model and dependencies privately in Windows installer; no persistent PATH/environment changes. Preserve existing work. User subsequently explicitly requested one checkpoint commit of all current project work, not a completed release.

Checkpoint verification: independent verifier observed `mvn -o test` (134 passed), `node --test src/test/js/*.test.js` (24 passed), `node --check src/main/resources/web/app.js` (passed), and `python target/ocr-source-audit/verify_audit.py` (passed). This is a large initial project snapshot, not a review-sized PR. Exclude NUL, target binaries/downloaded archives/transient scripts, .atl and .codegraph; include this task document explicitly despite odd/ ignore. Audits refer to local target artifacts and cannot be fully rerun from this commit alone. GCC provenance and eight BUILDINFO recipe discrepancies remain pending. Commit identity is recorded in session memory after creation; no release approval or push implied.

- [x] Implement and test JAR-relative private OCR executable/model resolution (delegated writer). Incomplete OCR bundle rejects image processing only, not PDF construction.
- [ ] Implement and test isolated installer staging and update packaging docs/skill (same sequential writer). Implementation/docs done; actual staging and reparse-point fixture checks pending.
- [ ] Verify runtime and packaging; report missing redistribution provenance/licenses and native review outcome. Blocked on curated native dependency bundle and complete license/provenance evidence; no installer generated.

Evidence: writer observed focused RED/GREEN; final combined focused Maven suite 11 passing, full suite 134 passing; installer -Plan passes (does not run staging). Independent verifier repeated 11 passing tests before final deferred-validation/reparse hardening. Native inspect requires untracked scope selection; review not completed. Existing whole project remains untracked; do not claim a reviewed release. No commits created.

Strict TDD: enabled by AGENTS.md explicit user decision. Runners: mvn -o -Dtest=TesseractImageTextExtractorTest,PipelineFactoryTest,DesktopLauncherTest test; mvn -o test. Tests may write target/** and runner-cleaned OS temp fixtures.

Resume: user confirmed second-PC test on Windows x64. Local executable reports Tesseract v5.5.3.20260724 and PE machine 0x8664 (x64). Recursive installed-file inspection found only doc/LICENSE; no curated manifest exists under target. Build remains blocked by curated dependency/provenance/notice input; no validation bypass, binary copy, or installer build performed.

## License/source completion (authorized follow-up)

- [ ] Archive official license/source evidence for the 33-DLL closure and Spanish model, with per-file hashes and unresolved matches explicit.
- [ ] Verify evidence coverage and applicable source obligations before declaring point 1 complete.

Route: parent official web research (child lacks web tools), then delegated artifact assembly; documentation/evidence only, no runtime changes or fabricated RED. No installer generation in this follow-up.

## Online provenance investigation

Web fetch now works in parent (not in explore child). Retrieved official release metadata at https://api.github.com/repos/tesseract-ocr/tesseract/releases/tags/5.5.3 and UB Mannheim wiki. Original Downloads installer size 26573224 and SHA256 bee9e3434bd94fd65387d9be28cd467a41f61b1275383b55b0f59a1331270ae4 match official asset.

Local spa SHA256 6f2e04d02774a18f01bed44b1111f2cd7f3ba7ac9dc4373cd3f898a40ea6b464; Git blob 72e901f13ca52cfe34cf239a368b9ed3c0ddaf26 matches https://api.github.com/repos/tesseract-ocr/tessdata_fast/contents/spa.traineddata. Official model LICENSE retrieved (Apache-2.0).

Build recipe: https://raw.githubusercontent.com/UB-Mannheim/tesseract/9f6f68962dbd839d2e5a296d17badf5666d3fe60/nsis/build.sh uses MSYS2 packages plus separate GCC runtime DLLs; no exact package-version lock in recipe. NSIS recipe uses tessdata_fast/main and copies only upstream LICENSE/AUTHORS/README for documentation, explaining missing third-party notices.

Independent read-only PE analysis found 33 local DLLs required statically by tesseract.exe, all x64, 11 external System32 names present and no unresolved imports; dynamic loads/real runtime still unverified. Full dependency/hash list retained in verification task result. Avoid copying training dependencies unnecessarily.

Important: https://packages.msys2.org/package/mingw-w64-x86_64-jbigkit lists libjbig-0.dll and GPL-2.0, with COPYING and source archive. This DLL is in local static closure. Current package page describes 2.1-6 built after our installer, so exact local build/version match remains unresolved. Notices alone must not be represented as sufficient distribution compliance; corresponding sources and applicable obligations require review. No EXE generated in this investigation.

Forecast: approximately 250-400 authored diff lines, one bounded packaging/runtime slice. No source binary vendoring. Current installed Tesseract includes spa and many DLLs; complete dependency license provenance not yet verified. Do not claim redistributable installer ready without it.

### Evidence assembly update

Partial evidence was assembled under `packaging/windows/ocr-evidence/` and `target/ocr-source-audit/`: all 34 supplied native files plus `spa.traineddata` have local SHA-256 records; the official Tesseract and tessdata_fast license texts, the JBIG-KIT 2.1 source archive, its extracted `COPYING`, and all five pinned MSYS2 patches were archived and hash-verified. The 27 historical MSYS2 recipes and pinned UB Mannheim build/NSIS recipes are also preserved with their hashes and parsed metadata.

At this stage, the historical recipes were candidate provenance rather than hash-matched binary correspondence. Subsequent source-text and binary-candidate updates below supersede the former non-JBIG text-evidence gap; GCC provenance remains unresolved.

### Candidate source-text collection update

The first recipe-declared source archive for each non-JBIG mapped component was collected without sourcing or executing any PKGBUILD. Twenty-five archives were retained under `target/ocr-source-audit/archives/` with matching declared SHA-256 values; relevant non-HTML `COPYING`/`LICENSE`/`NOTICE`/`AUTHORS` members were extracted for all 25 components into per-component evidence directories and carry archive/version/hash metadata. `winpthreads` remains distinct because its pinned recipe names a commit-addressed git source, not an HTTPS archive. This improves candidate source-license evidence but does not prove a local DLL binary match or resolve GCC provenance.

### Remaining text-gap closure update

Windows `tar.exe` (libarchive) successfully listed and streamed the bounded, named `gettext` 1.0 text members from the already hash-verified `.tar.lz`; all 25 recipe-declared HTTPS archives now have extracted text evidence. `winpthreads` now has the exact commit-addressed upstream `COPYING` and the matching pinned MSYS2 patch (SHA-256 `2e779bcc60a1422b23e0cfdb5c0f6851f2382592bb4675d08a6bdef78d5e5c10`), but no full git source archive was invented. GCC 13.4.0 `COPYING.RUNTIME` and `COPYING3` were retained as explicitly candidate-only references; observed local strings conflict (`13-win32`, `14-posix`, and a `posix` build path), so no installed-runtime version, corresponding source, or Runtime Library Exception applicability was inferred. The evidence table now lists per-component next obligations.

Validation: `powershell.exe -NoProfile -Command '& "$env:WINDIR\\System32\\tar.exe" -tf "target/ocr-source-audit/archives/gettext/gettext-1.0.tar.lz"'` listed the archive; `python target/ocr-source-audit/collect_candidate_licenses.py` reported `gettext=archived_and_license_texts_extracted`; `python target/ocr-source-audit/verify_audit.py` passed with 135 evidence files, 25 candidate source archives, the winpthreads license/patch, and GCC candidate references hash-verified.

### MSYS2 binary-candidate correspondence update

One recipe-derived x86_64 MSYS2 package URL was fetched and inspected for each of the 27 mapped components. Every retained archive has matching `.PKGINFO` `pkgname` and combined `pkgver-pkgrel` metadata; 30 archive DLL members were compared byte-for-byte with the local closure. Twenty-nine match exactly. The sole mismatch is `libssh2-1.dll`: candidate `mingw-w64-x86_64-libssh2-1.11.1-2-any.pkg.tar.zst` member SHA-256 `a8e1cc06bfff6be779066c5dc34ce740ed0226dcb6f75f46d9c915aee19a21b1` differs from local SHA-256 `ee7fe3e749859334c99e9261689481dd879811122598d22585421f95d29fc7ae`. giflib 6.1.3 and libjpeg-turbo 3.2.0 candidates match, so no unverified alternative URLs were constructed. The winpthreads GitHub commit snapshot was archived; its `COPYING` matches the exact previously fetched text, while its archive SHA-256 is recorded only as observed, not upstream-proven. Artifact storage is 111,860,573 bytes, below the 500 MiB cap.

Validation: `python target/ocr-source-audit/collect_binary_candidates.py` reported 27 `member_hashes_compared` results; `python target/ocr-source-audit/verify_audit.py` passed with 27 binary packages, 30 DLL members (29 exact, one mismatch), plus original/native/source archive and metadata hashes recomputed. No bundle manifest or installer approval was created.

### Remaining binary-candidate update

The rejected OpenSSL `libssh2` package candidate remains preserved as mismatch evidence, while the recipe-defined `libssh2-wincng` `1.11.1-2` package has matching `.PKGINFO` metadata and an exact `libssh2-1.dll` member hash. The exact frozen UB Mannheim workflow is retained and declares `ubuntu-24.04`. Both approved Ubuntu Noble GCC runtime `.deb` files match their supplied size and SHA-256, but each of their win32/posix `libgcc_s_seh-1.dll` and `libstdc++-6.dll` members mismatches the local files; neither runtime data archive contains copyright text, so the matching base package is required rather than inferred. Installed `tesseract.exe` and `libtesseract-5.dll` exactly match members of the official, hash-verified installer. The 5.5.3 source tag and frozen UB recipe are recorded as associated source evidence; a reproducible build is not claimed or required for that installer provenance observation.

For matched MSYS2 packages, `PATCH_PROVENANCE.md` now enumerates all 39 bare recipe-declared downstream patches with exact pinned URLs: 6 are archived and 33 remain explicitly unarchived. Remote patch URLs remain in the audit's recipe source fields. Validation: `python target/ocr-source-audit/collect_remaining_correspondence.py` reported `wincng=member_hash_compared`, two `deb_members_compared`, and `tesseract=installer_members_compared`; the structural verifier recomputed all recorded hashes. Artifact storage is 182,578,375 bytes, below the 500 MiB cap. Remaining blockers are downstream patch archival/review, release-grade winpthreads source-to-package provenance if required, and exact GCC base/compiler provenance. No bundle manifest or installer approval was created.

### Patch archival and GCC PE update

The 33 missing recipe-relative patch URLs were attempted once without applying any patch: 32 archives now match their recipe SHA-256 values. `libarchive/backport-unzip.patch` returned HTTP 404 from the generated raw snapshot URL; its recipe checksum is `3c4048073aea70cebf9d9505b24eebd6216d667995ca4ec53320268c04dfd95d` and the recipe retains the distinct remote commit-patch URL, so no substitute bytes were invented. The patch inventory now reports 39 total bare patches: 38 retained/verified (the original JBIG five and winpthreads patch included) and one unavailable remote-source gap.

Both local GCC DLLs exactly match the original verified installer members, proving the original release shipped the installed pair. Both approved Ubuntu runtime `.deb` artifacts match their supplied SHA-256/size, but all four win32/posix comparisons have different `.text` section hashes, not metadata-only differences. Local PE timestamps are `1743200491` versus Ubuntu candidates `1695580465`; local compiler markers include GCC 14-posix alongside 13-win32 strings, while Ubuntu candidates expose GCC 13 variant markers. The win32 candidates omit `libwinpthread-1.dll` from imports where the local pair imports it; the posix import sets align but `.text` still differs. Runtime package copyright text is absent, requiring the matching base package rather than inference. Validation: `python target/ocr-source-audit/collect_downstream_patches.py` recorded 32 checksum-verified patches and one exact HTTP 404; `python target/ocr-source-audit/verify_audit.py` recomputed source/binary/patch/PE-status hashes. No bundle manifest or installer approval was created.

### Final backport patch and BUILDINFO update

The exact recipe-declared libarchive commit patch `https://github.com/libarchive/libarchive/commit/4558c310de7c08d2156aac733551a6ba98f642d2.patch` is now retained as `backport-unzip.patch` and matches recipe SHA-256 `3c4048073aea70cebf9d9505b24eebd6216d667995ca4ec53320268c04dfd95d`. The original generated MSYS2 raw-directory URL HTTP 404 remains preserved in patch metadata as historical evidence. All 39 bare downstream patches are now archived and checksum-verified; none used `SKIP` or an unmapped checksum.

`pkgbuild_sha256sum` from each original candidate package `.BUILDINFO` was compared to the archived pinned PKGBUILD. Nineteen of 27 match exactly. Eight differ: `jbigkit`, `libb2`, `brotli`, `bzip2`, `libdeflate`, `lz4`, `libpsl`, and `libwebp`. Their DLL members still match the candidate package archives, but their archived historical recipe/source/patch set must remain candidate evidence rather than an exact build-recipe association. GCC 13.4 references remain candidate license texts only; they are not corresponding sources for the installed GCC DLLs. Final validation: `python target/ocr-source-audit/verify_audit.py` passed with 39 verified patches, 19/27 BUILDINFO recipe-hash matches, exact Tesseract installer-member provenance, exact MSYS2 package-member evidence, and explicit GCC candidate mismatches. Point 1 remains incomplete for exact GCC base/compiler source provenance, the eight BUILDINFO recipe-hash discrepancies, and any required release-grade winpthreads source-to-package path. No legal compliance or packaging readiness is claimed.
