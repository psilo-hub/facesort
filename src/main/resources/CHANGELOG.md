# Changelog
All notable changes to Face Sort will be documented in this file.

## [1.0-SNAPSHOT]

### Added
- Export a person's photos from the "Add faces to a name" tab: pick any name,
  choose an output folder, and every image containing that person is copied
  there; images whose original file is gone are exported as thumbnails instead
  (2026-09-19)
- Shift-click selects a range of candidate faces in the "Add faces to a name"
  tab (2026-09-19)
- Internationalization: the UI language can be changed in the Settings tab
  (English, Deutsch, Français, Español, Русский, 中文) and is saved in the
  config file; all UI strings are externalized (2026-09-19)
- Tabs now show a unicode emoji before their name (2026-09-19)
- Translated READMEs: README.de.md, README.fr.md, README.es.md, README.ru.md,
  README.zh.md (2026-09-19)
- Bundled the ffmpeg4j FFmpeg library (new scaffolded dependency for the
  upcoming video import feature) — nothing to install (2026-09-19)
- Database schema + DAO layer for the upcoming video import feature: new
  `videos`, `video_paths` and `video_frames` tables (referencing the video file
  as the source) with a `VideoDao` for inserting videos, tracking their file
  paths production line and linking frames/faces to a video. No user-visible UI
  yet, this is the data foundation (2026-09-20)
- Video import foundation: the frame sampling math for the upcoming video
  import feature — at most one frame per second and at most 120 frames per
  video, evenly spaced in time (internal, no UI yet) (2026-09-20)
- Video import foundation: `VideoFrameUtils.rgb24ToImage` converts the packed
  RGB24 bytes delivered by ffmpeg frame decoders into a `BufferedImage`, and
  `HashUtils.hashBytes` computes the SHA-256 of raw byte content (used to
  fingerprint extracted frames later). Internal plumbing for the upcoming video
  import feature, no UI yet (2026-09-20)

### Changed
- Tabs renamed (2026-09-19)
- Changelog shortened (2026-09-19)
- The UI refreshes immediately when the language is switched (2026-09-19)

### Fixed
- Tab names no longer clip descenders (taller tab headers) (2026-09-19)
