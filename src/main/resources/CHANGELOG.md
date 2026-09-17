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

### Changed
- Optimized image import for large folders.
- Consistent selection behavior: left-click selects, right-click opens the
  context menu.
- In "Face Name", faces with the highest similarity to the selected name's
  average embedding are shown first, and a checkbox can exclude faces that are
  closer to another name's average embedding.

## [0.1.0]

### Added
- Initial release.