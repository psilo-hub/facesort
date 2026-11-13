# FaceSort — Improvement Todo List

## Clean code

- [ ] Reduce pom.xml duplication - the 5 platform profiles each duplicate ~110 lines of shade-plugin configuration; parameterize the transformer and native-exclusion blocks
- [ ] Pin GitHub Actions to commit SHAs (actions/checkout, setup-java, cache, upload-artifact, download-attr, softprops/action-gh-release) - requires internet access to look up SHA values; not possible in this offline environment

## Robustness

- [ ] Make offline startup work when FaceAI models are already cached - FaceSortApp.start() wraps FaceAiService.modelsDownloaded() in try/catch so a network failure during the check no longer crashes startup; on download failure offline, modelsDownloaded() is re-checked before giving up so a previously downloaded library starts without internet (FaceSortApp.java:149-160, FaceAiService.java:58-81)
- [ ] Add progress reporting and cancel to large removals in RemoveByPrefixDialog - Cancel is enabled during removal, progress is reported in the result label, and cancelling rolls back the transaction

## Testing / TDD

- [ ] Extend headless test seams for SettingsView, DedupeView, NameFaceView and RandomNameView - none of the four has a test class and all build JavaFX controls inline
- [ ] Add headless test seams for TagWithNameDialog, ImportView, MostSimilarView and MainWindow - none has a test class and all build JavaFX controls inline

## Build / CI

- [ ] Add a CI test job running mvn test on pull requests and make the release job depend on it - build.yml has a test job that installs the FaceAI/ffmpeg4j dependencies, installs JavaFX system libraries and runs the full suite under Xvfb on every push and pull request; release now needs test as well as the two build jobs
- [ ] Reduce pom.xml duplication - the 5 platform profiles each duplicate ~110 lines of shade-plugin configuration; parameterize the transformer and native-exclusion blocks
- [ ] Pin the FaceAI dependency to a commit SHA in CI - ffmpeg4j is already pinned to a SHA, FaceAI is still cloned from the default branch (build.yml)
- [ ] Pin GitHub Actions to commit SHAs (actions/checkout, setup-java, cache, upload-artifact, download-attr, softprops/action-gh-release) - requires internet access to look up SHA values; not possible in this offline environment

## UI / UX

- [ ] Add keyboard shortcuts, mnemonics and accessible text - no accelerator, mnemonic or setAccessibleText call exists anywhere in the UI today

## Features

- [ ] Expose a GPU inference option in settings once the FaceAI library supports device selection
- [ ] Add the ability to re-detect faces after detection settings change
- [ ] Add a single-level undo buffer for the last tagging and dedupe actions (dedupService.merge has no reversal path, DedupeView.java:245)
- [ ] Document the video import feature in the translated READMEs (de, es, fr, ru, zh) - only README.md mentions MP4/MKV/MOV/AVI; every translation still says photos only

## Pending from further_improvements.txt

1. When tagging an image or images with a name (in the "Put a name to a face" and the "Tag random face" tab) that name should be selected or jumped to when switching to the "add faces to a name" tab.
2. Whenever a path filter is set in a tab, that path is stored somewhere in a global variable. When switching to a tab with a path filter, the stored value is set as the path filter.
3. Switching tabs shouldn't interrupt running tasks. For example, if I switch from the "Import images" tab to another tab, a running import task should keep running until it's finished.

## New Suggestions

- [ ] Add keyboard shortcuts, mnemonics and accessible text - implement accelerators for common actions (tag, skip, reject), mnemonics for menu items, and setAccessibleText on all JavaFX controls to support screen readers
- [ ] Add a single-level undo buffer for the last tagging and dedupe actions - provide a reversal path for dedupService.merge and tagging actions, allowing users to undo the last operation
- [ ] Expose a GPU inference option in settings once the FaceAI library supports device selection - add a settings entry to select between CPU and GPU inference, with appropriate fallbacks
- [ ] Add the ability to re-detect faces after detection settings change - provide a mechanism to re-run face detection on imported images when detection parameters (threshold, minimum face size) are adjusted
- [ ] Add batch review of the top-K most similar pairs - enable reviewing and deciding on multiple name-face pairs in one session, replacing K full rescans with roughly one
- [ ] Wire up the video import feature in the translated READMEs (de, es, fr, ru, zh) - update all translation bundles to mention MP4/MKV/MOV/AVI video support alongside photos