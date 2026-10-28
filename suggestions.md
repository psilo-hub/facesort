# FaceSort — Improvement Todo List

## Clean code

- [x] Remove the 8 test-only DAO methods: NotDupeDao.isNotDupe/findByNameId/deleteForName, FaceDao.findAll/delete, VideoDao.findByHash, ImageDao.findByHash/getAllHashes are gone from src/main (NotDupeDao.findAll kept). Tests that needed the removed reads now use shared queries: DbTestQueries.allImageHashes for the image-hash list, countRows for face/not-dupe counting, notDupeDao.findAll for the stored-pair check; the 7 test methods that tested the deleted methods themselves were dropped (652 → 645 tests)
- [ ] Extract the duplicated FaceUi.FaceActions.Source bridge, handleFailure and originalCheckFailed error lambda shared by all six views (NameFaceView.java:72/414, ViewView.java:87/460, DedupeView.java:77/360, RandomNameView.java:72/396, FaceNameView.java:89/721, MostSimilarView.java:72)
- [x] Consolidate ViewService's duplicated path resolution and extract MediaFileResolver shared with StoredFaceImageSource.firstReadableFile - resolveOriginalFile/resolveFilterPath/openInDefaultViewer/openContainingFolder/resolveContainingFolder/firstExistingPath now live in MediaFileResolver; ViewService, DeduplicationService, FaceToNameService, NamingService and StoredFaceImageSource all delegate to it
- [ ] Extract shared UI helpers: TagWithNameDialog.toImage duplicates FaceUi.toImage, SettingsView.tooltip duplicates FaceUi.wrappedTooltip including the magic 420, RandomNameView.java:114 still builds a bare Tooltip, and applyFilterPath is duplicated in FaceNameView.java:541 and RandomNameView.java:312
- [ ] Clean up small code smells - unused CSS selectors (.grid-of-faces, .candidate-check), modal-error Javadoc copied across six views, misleading getText() == null checks on never-null TextField.getText(), the magic constant 420 in FaceUi/SettingsView, inconsistent constructor null-check styles across services
- [x] Reduce ConfigModel's 21 with* copy-method fan-out with a toBuilder() while keeping the record immutable - adding one config field now touches the Builder once instead of every with* method
- [ ] Refactor views to depend on narrow service interfaces instead of concrete fat services for testability (DedupeView uses DeduplicationService, NameFaceView/RandomNameView use NamingService, ViewView uses ViewService, FaceNameView uses FaceToNameService)

## Robustness

- [x] Make offline startup work when FaceAI models are already cached - FaceSortApp.start() wraps FaceAiService.modelsDownloaded() in try/catch so a network failure during the check no longer crashes startup; on download failure offline, modelsDownloaded() is re-checked before giving up so a previously downloaded library starts without internet (FaceSortApp.java:149-160, FaceAiService.java:58-81)
- [x] Add progress reporting and cancel to large removals in RemoveByPrefixDialog - Cancel is enabled during removal, progress is reported in the result label, and cancelling rolls back the transaction

## Testing / TDD

- [ ] Extend headless test seams for SettingsView, DedupeView, NameFaceView and RandomNameView - none of the four has a test class and all build JavaFX controls inline
- [ ] Add headless test seams for TagWithNameDialog, ImportView, MostSimilarView and MainWindow - none has a test class and all build JavaFX controls inline
- [ ] Add i18n bundle-consistency tests - every key referenced in source must exist in the base bundle and all five translations must match its key set (I18n.text silently returns the raw key on a miss; the six bundles are at parity today with 297 keys each)
- [x] Add a headless TaskRunnerTest with a runLater/executor seam covering success, failure propagation and cancellation before/after start (TaskRunner has no direct test; bugs in this area were found twice already)

## Build / CI

- [ ] Add a CI test job running mvn test on pull requests and make the release job depend on it (.github/workflows/build.yml currently builds with -DskipTests in every job)
- [ ] Add static analysis and a JaCoCo coverage gate with maven-enforcer-plugin
- [ ] Reduce pom.xml duplication - the 5 platform profiles each duplicate ~110 lines of shade-plugin configuration; parameterize the transformer and native-exclusion blocks
- [ ] Pin the FaceAI dependency to a commit SHA in CI - ffmpeg4j is already pinned to a SHA, FaceAI is still cloned from the default branch (build.yml)
- [ ] Pin GitHub Actions to commit SHAs and establish a release versioning scheme (releases are only tagged build-N)

## i18n

- [ ] Wire up or remove the unused i18n key ui.faceName.pathFilterPrompt - defined in all six bundles but never referenced in code (FaceNameView uses a label and tooltip instead of prompt text)
- [ ] Unify similarity score formatting on I18n.percent - three approaches coexist: I18n.percent (FaceNameView/NameFaceView/MostSimilarView), raw similarity*100 with %.0f%% in the bundle (TagWithNameDialog.java:174), and raw fraction with %.2f in ui.dedupe.round (DedupeView.java:216)
- [ ] Refactor I18n to reduce global mutable state - static currentLocale/bundle fields and every lookup synchronized (I18n.java:28-94)

## UI / UX

- [ ] Add keyboard shortcuts, mnemonics and accessible text - no accelerator, mnemonic or setAccessibleText call exists anywhere in the UI today
- [x] Add sorting to ViewView and FaceNameView by name, count or recency - sort dropdown added to both views (name ascending via Collator, face count ascending/descending); recency not available since NameRecord has no timestamp field

## Features

- [ ] Add a library backup/export action that snapshots facesort.db (plus its WAL sidecar) or exports faces/names/images to a user-chosen folder - only per-name image export exists today (FaceToNameService.exportImagesForName)
- [ ] Expose a GPU inference option in settings once the FaceAI library supports device selection
- [ ] Add the ability to re-detect faces after detection settings change
- [ ] Add a single-level undo buffer for the last tagging and dedupe actions (dedupService.merge has no reversal path, DedupeView.java:245)
- [ ] Add EXIF orientation handling in ImageUtils.readImage so phone photos import upright - no exif/orientation handling exists anywhere in src/main/java (ImageUtils.java:107-140)
- [ ] Document the video import feature in the translated READMEs (de, es, fr, ru, zh) - only README.md mentions MP4/MKV/MOV/AVI; every translation still says photos only
