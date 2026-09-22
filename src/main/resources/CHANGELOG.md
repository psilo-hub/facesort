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
- Video import foundation: `VideoFrameSource` interface — the test seam for
  reading sampled frames out of a video (duration probe, forward-only seek to a
  target, frame + real position delivery, graceful EOF). Keeps the video import
  logic testable without the ffmpeg native library (2026-09-20)
- Video import foundation: `FakeVideoFrameSource` (in-memory test double) and
  `VideoFrameSourceTest` pin down the frame-source contract — one ascending
  frame per `FrameSampler` target, frame + real position delivery, forward-only
  seeking (rewind rejected), graceful end of stream. Internal plumbing, no UI
  yet (2026-09-20)
- Video import foundation: `FfmpegVideoFrameSource` — the real frame-source
  implementation wrapping ffmpeg4j (ffmpeg via JNI, nothing to install).
  Opens any supported video, probes its duration, and streams one sampled
  frame per second (at most 120 per video) in a single forward pass, reporting
  each frame's real position. Videos with an unreadable header fail cleanly
  instead of crashing. Internal plumbing, no UI yet (2026-09-20)
- Video import service: `VideoImportService` imports video folders end-to-end —
  content-hash deduplication (known videos are skipped, same content under a
  new path only records the path), ffmpeg frame extraction and face detection
  for every sampled frame, full-frame thumbnails, per-file error resilience,
  parallel multi-engine workers, progress listener and cancellation. Frames
  that collide with already-stored content (an identical frame or a photo)
  reuse the existing image row and are only linked to the video. Photos and
  video frames are now analysed through one shared detection pipeline so their
  results are identical. No user-visible UI yet — the Import tab still imports
  images only (2026-09-20)
- Video import reaches the Import tab: pressing **Import** now processes photos
  **and** videos in one run — photos first, then videos — streaming both phases
  into the same log with one Stop button. Each video is sampled at most once
  per second (120 frames max), every detected face is stored like a photo face,
  and the final status shows a photo summary and a video summary (total / new
  videos / frames / faces / skipped / errors). Known videos are skipped on
  re-import, and cancelling mid-run leaves already-imported files in place
  (2026-09-20)
- The Import tab's folder picker, progress messages and summaries mention
  photos **and videos** in all six languages (2026-09-20)
- Video faces work across the tagging and review features: a face tagged on a
  video frame shows its frame thumbnail in the View tab and in "Add faces to a
  name", is exported with a person's photos (as the frame thumbnail, since a
  video frame has no photo original), and is clustered, deduped and ranked
  exactly like a photo face (2026-09-20)
- The path-prefix filter (in "Tag random face" and "Add faces to a name") now
  also matches video faces through their video file's path, so filtering by
  folder or file works for videos too (2026-09-20)
- "Open Original" on a video face now opens the source video the frame came
  from in the system default player (2026-09-21)
- Right-click a face in "Tag random face" or "Add faces to a name" and choose
  "Paste path to path filter" to fill the path filter with that face's folder —
  the source video's folder for video faces; when several paths are stored an
  existing one is preferred (2026-09-21)
- Right-click an unnamed candidate face in "Add faces to a name" and choose
  "Tag with a different name" to tag that face with a name other than the one
  it was offered under: a dialog shows the selected face, lets you type a name,
  announces new names and previews existing names with their most representative
  face and the similarity to the name's average embedding (2026-09-22)

### Changed
- Tabs renamed (2026-09-19)
- Changelog shortened (2026-09-19)
- The UI refreshes immediately when the language is switched (2026-09-19)

### Fixed
- Tab names no longer clip descenders (taller tab headers) (2026-09-19)
- Release jars include video import: the CI build now publishes a jar per
  platform (Linux x86-64/ARM64, macOS x86-64/ARM64, Windows x86-64) plus a
  universal fat jar on every push to main, each platform jar bundling only that
  platform's native libraries (~105–111 MB) with the fat jar (~243 MB) covering
  all of them. The video import feature is fully contained in the released jars
  (2026-09-21)
- Tab headers now render with the bundled fonts: the tab emoji uses the bundled
  Noto Emoji font and the tab text the bundled Noto Sans font, so the tabs look
  the same on every system; the View and Settings tabs no longer show a stray
  variation-selector character (their emoji changed to 🔎 and 🔧) (2026-09-21)
