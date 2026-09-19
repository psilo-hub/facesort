# Changelog

All notable changes to Face Sort will be documented in this file.

## [1.0-SNAPSHOT]

### Added
- Face detection, embedding, clustering and tagging of faces in imported photos.
- "Name Face", "Random Tag", "Face Name", "Deduplicate" and "View" tabs for
  organizing and reviewing faces.
- Rename feature: quickly rename an existing person across all their faces.
- Untagging feature: remove a face from a name.
- Background update check that notifies when a new release is available and
  links to the latest release on GitHub.
- Configurable similarity cutoff when adding faces to an existing name.
- Explanation tooltips on settings labels and a persisted reset-to-defaults.
- Hovering an image in the "Name Face" and "Random Tag" tabs shows the image
  path as a pop-up.
- Setting to disable the automatic update check on startup.
- Setting to configure the maximum number of images shown as candidates in
  the "Face Name" tab.
- Path-prefix filter in the "Random Tag" and "Face Name" tabs: entering a
  path (or path prefix) restricts the shown faces to images whose stored path
  starts with that text; in "Face Name" it filters the "Most similar unnamed
  faces".
- On first start, a model-download frame shows the file being downloaded
  (its URL), where it is stored locally and a progress bar until the models
  are ready.

### Changed
- Optimized image import for large folders.
- Consistent selection behavior: left-click selects, right-click opens the
  context menu.
- In "Face Name", faces with the highest similarity to the selected name's
  average embedding are shown first, and a checkbox can exclude faces that are
  closer to another name's average embedding.
- The minimum similarity for images shown in "Face Name" is now editable as a
  plain value in Settings instead of a stepped spinner.
- Wider path-filter fields in the "Random Tag" and "Face Name" tabs.
- Renamed the "Import", "Name Face" and "Random Tag" tabs to "Import images", "Put a name to a face" and "Tag random face".
- Renamed the "Face Name" tab to "Add faces to a name".

### Fixed
- Right-clicking an image in the "View" tab now shows the context menu
  ("Open Original", "Untag from ...") instead of opening the original file
  in the default viewer.

## [0.1.0]

### Added
- Initial release.