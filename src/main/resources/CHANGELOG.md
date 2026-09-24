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
- Every context menu that offers "Open Original" now also offers "Open
  containing folder", opening the folder of the original source file (the
  source video's folder for video frames) in the system file manager
  (2026-09-22)

### Changed
- Tabs renamed (2026-09-19)
- Changelog shortened (2026-09-19)
- The UI refreshes immediately when the language is switched (2026-09-19)
- Test suite hardened (internal, no visible behavior change): the video-import
  end-to-end tests now run in every environment — a tiny test video is
  generated in pure Java instead of the tests being skipped when the
  git-ignored sample files are missing — and the import-parallelism,
  face-detection error-path, naming-rank and database FK-cascade behaviors
  gained deterministic assertions and new coverage (2026-09-23)
- Database access is now single-lock: all DAO calls from any thread are
  serialized through one synchronized connection, so concurrent import
  workers, video import and the clustering/naming/dedup services can never
  interleave on the shared SQLite connection. Redundant per-service locks were
  removed, and importing the same content from parallel workers (photos or the
  same video frame) now resolves to "already imported" instead of an error
  (2026-09-22)
- Multi-statement database writes are now transactional: a photo import (image
  row + file path + faces), a video frame import (frame row + thumbnail +
  faces + video link) and a dedupe merge (reassign faces + delete name) each
  commit as one atomic unit, so a failure mid-write no longer leaves orphaned
  or half-updated rows behind (2026-09-23)
- The database schema is now versioned: `PRAGMA user_version` tracks the schema
  version and existing databases are brought up to the current schema when they
  are opened, so future schema changes can migrate old data instead of silently
  missing columns (internal, no visible behavior change) (2026-09-23)
- The photo and video import pipelines share one worker pool, one recursive
  file walker and one thumbnail encoder (internal, no visible behavior change):
  the parallel worker-thread pool with cancellation/progress/error counting,
  the `.extension` file walk and the JPEG thumbnail encoding were extracted from
  `ImportService`/`VideoImportService` into shared helpers, and a single JPEG
  quality constant applies to thumbnails and face sub-images (2026-09-23)
- The faces table is always read through a single shared column list (internal,
  no visible behavior change): the ten columns projected by every `FaceDao`
  query live in one `SELECT_COLUMNS` constant instead of six duplicates, with a
  test pinning it to the columns the row mapper reads and to the physical
  schema (2026-09-23)
- Names are always read through a single shared SELECT + row mapping (internal,
  no visible behavior change): the correlated face-count subquery and the
  `NameRecord` mapping that were repeated in each `NameDao` read method now live
  in one place, covered by a test that all three readers compute the same face
  count (2026-09-23)
- CI builds are faster: both build jobs cache Maven's local repository
  (`~/.m2`) per platform, so the JavaFX/native dependency downloads no longer
  repeat on every run. The FaceAI / rawGitHubFetcher / ffmpeg4j dependency
  builds still run fresh, so build results are unchanged (2026-09-23)
- Supported video formats are defined in one place (internal, no visible
  behavior change): the import walk and the ffmpeg frame source now share a
  single extension → demuxer map (`VideoFormats`) instead of two parallel
  tables that could drift, with tests pinning them together (2026-09-23)
- The name lookup/create logic is shared (internal, no visible behavior change):
  the trim → find → insert helper behind "create or find name" and "find name"
  now lives once in a shared `NameService`, used by both tagging workflows
  (2026-09-23)
- The "representative face" selection is shared (internal, no visible behavior
  change): computing the average embedding of a set of faces and picking the
  face closest to it is now one `FaceSelector` helper, used by the clustering,
  deduplication, View-tab and "Add faces to a name" services instead of four
  near-identical reimplementations (2026-09-24)

### Removed
- Dead code cleaned up (no behavior change): the unused `FaceThumbnail` and
  `ProgressDialog` UI components, the unused `ImageDao.delete`/`VideoDao.delete`
  methods, and unused model/utility API (`FaceRecord` bounding-box helpers,
  `ImageRecord` path/thumbnail fields, `NameRecord.withFaceCount`,
  `EmbeddingUtils.cosineSimilarity`, `ImageUtils.crop`) are gone. `not_dupes`
  pairs now use an immutable `NamePair` record (2026-09-23)
- Database schema version 2: the stored `videos.frame_count`/`face_count` and
  `video_frames.face_count` columns are dropped when a v1 database is opened —
  they were written but never read (frame and face counts are computed live);
  existing databases migrate automatically (2026-09-23)

### Fixed
- Feedback submissions and the automatic update check now give up instead of
  hanging: both requests have a bounded network timeout (15 s feedback, 20 s
  update check), so a stalled connection cannot leave the app waiting. The
  update check's downloaded changelog is also written atomically, so an
  interrupted write can never leave a truncated cache behind (2026-09-23)
- Hand-edited config files can no longer break the app at runtime: values such
  as a confidence above 100 %, a zero HNSW `M`, or a negative thread count are
  clamped to safe ranges when the config is loaded (2026-09-23)
- The "Saved" confirmation after changing the UI language is now actually visible:
  it is shown on the freshly rebuilt window instead of being attached to the view
  that the rebuild replaces (2026-09-23)
- Detected faces that fail to process are no longer silently lost: an image or video
  whose faces could not be cropped/embedded/encoded now counts as an error in the
  import summary, while the remaining faces (and the file itself) are still stored
  (2026-09-23)
- Similarity percentages are formatted consistently in the "Add faces to a name" and
  "Put a name to a face" tabs, rounded to a whole percent with the active locale
  (2026-09-23)
- Update-check and browser-open failures are logged via `java.util.logging` instead of
  being printed to the console; the import progress bar uses the standard indeterminate
  progress constant (internal polish, no visible behavior change) (2026-09-23)
- No more cross-wired results in the tagging views: when actions overlap (switching
  names, re-loading samples, typing a name, running a dedupe session), a finished
  background task can no longer apply a newer task's value. Every background
  operation now reports its own result, so face lists, similarity lists, name
  previews and dedupe pairs always match the action that started them (2026-09-22)
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

16. ✅ Face-grid UI dedup (suggestion 3b of suggestions.md): the five face-related views (`View`, `Dedupe`, `FaceName`, `NameFace`, `RandomName`) no longer each carry their own copy of the duplicated cluster — `installContextMenu`, the `isOriginalAvailable`/`isContainingFolderAvailable`/`openOriginal`/`openContainingFolder` quartet, `checkNameExists`, `installPathTooltip`/`tooltip`, `setImage`/`setImage(ImageView, FaceRecord)`/`faceCard` and the `handleFailure` Alert. A new shared `ui/FaceUi` helper owns it once: `faceCard(FaceRecord, double)` (shared `.candidate-card` CSS + wrapped tooltip), `setImage`/`thumb`/`toImage` (ImageView<-FaceRecord conversion), `installPathTooltip(Node, FaceRecord, String threadName, Lookup<List<String>>)` + `wrappedTooltip`, `checkNameExistence(...)` (same green/red hex + name-equality staleness guard), the nested `FaceActions` with a `Source` seam (`isOriginalAvailable`/`isContainingFolderAvailable`/`openOriginal`/`openContainingFolder`) + `installFaceMenu(Node, FaceRecord, MenuItem...)`/`installMenu`, and `showError(Window, String, String, Throwable)`. Each view keeps only its small private wiring (per-view i18n key prefixes, thread names, staleness guards). Full suite green (306 tests).
