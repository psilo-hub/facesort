# Video Support — Implementation Plan

Goal (todo.txt item 3): let the app tag faces in videos. Frames are extracted
from each video with **ffmpeg4j**, face detection runs on the extracted frames,
and the **thumbnails of the frames and the faces** are stored in the database
**referencing the video file as the source**. The extracted frame *files* can be
discarded afterwards.

This file is a working todo list: each item below is ticked off (`- [ ]` →
`- [x]`) as it is implemented, following the repo conventions in `AGENTS.md`
(TDD first, small atomic commits, and `todo.txt` + `CHANGELOG.md` + `README.md`
updates in the same commit as every user-visible change).

---

## 1. Design decisions (already settled)

- **UI**: extend the existing **Import** tab. The folder scan accepts image *and*
  video files; the run imports images first, then videos, into the same log /
  progress / stop controls. (Confirmed with the user.)
- **Dependency**: add ffmpeg4j the same way FaceAI and rawGitHubFetcher are
  handled — **clone the repo and `mvn install` it** in CI and for local builds,
  pinned to a specific commit. No jitpack repository. (Confirmed with the user.)
- **Frame budget** (deadline from the task): at most **one frame per second**,
  at most **120 frames per video**, frames **evenly spaced in time**. Limits are
  hard-coded constants for now (`MAX_FRAMES_PER_VIDEO = 120`,
  `MIN_FRAME_MS_SPACING = 1000`); promoting them to Settings is future work.
- **Frames are processed in memory**, one at a time, and never touched again
  afterwards — "the extracted frames can be deleted after that" is satisfied
  without temp files (face + frame thumbnails live in the DB).
- **Supported video formats** (case-insensitive): `mp4, mkv, mov, avi, webm,
  m4v, flv, mpg, mpeg, 3gp, ts, wmv`. Easy to extend in one set.
- **Re-import**: like images, a video is identified by its SHA-256 content hash;
  re-importing an already-known video is a no-op (or records an extra path).

---

## 2. How ffmpeg4j works (research notes)

`com.github.manevolent:ffmpeg4j` (repo `Manevolent/ffmpeg4j`, master
`5.1.2-1.5.8-1`) wraps **JavaCPP's bytedeco FFmpeg `5.1.2-1.5.8`** via JNI — no
external `ffmpeg.exe` is needed.

Relevant API (verified against the source):

- `FFmpegIO.openInputStream(InputStream, bufferSize)` → `FFmpegInput` →
  `input.open("mp4")` → `FFmpegSourceStream`.
- `sourceStream.registerStreams()` fills a list of `MediaSourceSubstream`s;
  pick the `VideoSourceSubstream` (first one). Error if there is none.
- The stream decoder converts every frame to the requested pixel format. The
  default is already `AV_PIX_FMT_RGB24` (3 bytes/pixel), set via
  `sourceStream.setPixelFormat(...)` **before** `registerStreams()` — keep the
  default.
- `sourceStream.seek(double seconds)` reads packets forward until it reaches
  the requested position (**forward-only**; throws `IllegalStateException` on
  rewind and `EOFException` past the end). Decoding can be switched off with
  `substream.setDecoding(false)` to make seeks cheap.
- `videoSubstream.next()` returns a `VideoFrame` with `getWidth()`,
  `getHeight()`, raw packed **RGB24** bytes in `getData()`, and the actual
  frame position (seconds) in `getPosition()` / `getTimestamp()`.
- Conversion RGB24 → `BufferedImage` is a simple per-row byte loop (R,G,B →
  `TYPE_INT_RGB` or `TYPE_3BYTE_BGR`).
- **Duration**: read from the format context
  (`FFmpegInput.getFormatContext().duration()`, microseconds, /1e6); if it is
  `AV_NOPTS_VALUE`/`<= 0`, fall back to a cheap packet scan with decoding off,
  using the last `sourceStream.getPosition()`.
- Native/platform layout: bytedeco ships `ffmpeg-platform` and `javacpp`.
  `-platform` jars bundle the natives for **every** OS under
  `org/bytedeco/ffmpeg/<os>-<arch>/...` (classifiers `linux-x86_64`,
  `linux-arm64`, `macosx-x86_64`, `macosx-arm64`, `windows-x86_64`,
  `windows-x86`, `windows-arm64`). The per-platform shaded jars in
  `.github/workflows/build.yml` must filter out all but the current platform →
  this is what the "platform dependent GitHub actions" note in todo.txt means.

---

## 3. Data model

No change to the existing `images`, `thumbnails`, `faces` tables — a video frame
**is** an image row. New tables (created additively in
`Database.initializeSchema()`, so existing databases migrate on next start):

- `videos (hash TEXT PRIMARY KEY, detection_ts INTEGER, criteria_json TEXT,
  duration_secs REAL, frame_count INTEGER DEFAULT 0, face_count INTEGER DEFAULT 0)`
- `video_paths (hash TEXT NOT NULL, path TEXT NOT NULL, PRIMARY KEY(hash,path),
  FOREIGN KEY(hash) REFERENCES videos(hash) ON DELETE CASCADE)`
- `video_frames (frame_hash TEXT PRIMARY KEY, video_hash TEXT NOT NULL,
  timestamp_ms INTEGER NOT NULL, face_count INTEGER DEFAULT 0,
  FOREIGN KEY(frame_hash) REFERENCES images(hash) ON DELETE CASCADE,
  FOREIGN KEY(video_hash) REFERENCES videos(hash) ON DELETE CASCADE)`

Each extracted frame gets:
- one row in `images` (hash = SHA-256 over the frame's full-frame JPEG bytes,
  `face_count` = faces found on it),
- one row in `thumbnails` (full-frame 256px JPEG under the frame hash),
- one row in `video_frames` linking frame hash → video hash + timestamp ms,
- zero or more rows in `faces` (`image_hash` = frame hash), exactly like photos.

Because `image_paths` is never populated for frames, "the video file is the
source" is only represented through `video_frames`/`videos`. Existing paths
filters (`FaceDao.findUnnamed(pathPrefix)`) therefore do not match video faces —
accepted limitation for this iteration (see §10).

---

## 4. Phases

### Phase 0 — Dependency and build setup

- [x] Pin ffmpeg4j: verify the exact commit of `Manevolent/ffmpeg4j` to use and
  record it (source of truth for CI and local installs).
  > Note: pinned commit is tag `5.1.2-1.5.8-1` →
  > `30d9a734a982b9b8a36e5cc800c4994d7e5c51ed`, recorded in `ffmpeg4j-pin.txt`.
  > Master has since moved to `5.1.2-1.5.8-4`; the immutable tag is pinned
  > instead so the installed artifact matches the planned dependency version.
- [x] Add to BOTH jobs of `.github/workflows/build.yml` (`build-fat` and
  `build-platform`) a step that clones ffmpeg4j at the pinned commit and runs
  `mvn -B install -DskipTests -f ffmpeg4j-deps/pom.xml`, mirroring the existing
  FaceAI / rawGitHubFetcher steps.
- [x] Add the dependency to `pom.xml`:
  `com.github.manevolent:ffmpeg4j:5.1.2-1.5.8-1` (resolved from the local repo).
- [x] Add per-platform shade-plugin filters (same pattern as the existing
  `pytorch-jni`/`sqlite-jdbc`/`jna` filters) to keep only the current platform's
  natives from the `org.bytedeco:ffmpeg*` and `org.bytedeco:javacpp*` artifacts:
  - `linux-x86_64` profile: exclude `linux-arm64`, `macosx-*`, `windows-*`
  - `linux-aarch64` profile: exclude `linux-x86_64`, `macosx-*`, `windows-*`
  - `mac-x86_64` profile: exclude everything but `macosx-x86_64`
  - `mac-aarch64` profile: exclude everything but `macosx-arm64`
  - `win-x86_64` profile: exclude `linux-*`, `macosx-*`, `windows-x86`,
    `windows-arm64`
  > Note: the per-profile lists above are shorthand for "keep only the current
  > platform's natives" (matching the existing jna/sqlite filter style). ffmpeg
  > 5.1.2-1.5.8 / javacpp 1.5.8 also ship `linux-x86`, `linux-armhf`,
  > `linux-ppc64le`, `android-*` (javacpp additionally `ios-*`) dirs, so the
  > implemented filters exclude those too.
- [x] Verify `mvn package -DskipTests` produces a working, reasonably sized
  shaded jar for the current platform (Windows) with ffmpeg4j on board.
- [x] Verify in CI: each platform job still builds and the shaded jars contain
  only their own platform's natives.
- [x] Docs: update `README.md` (Requirements: mention ffmpeg via ffmpeg4j is
  bundled, nothing to install), the translated READMEs if affected, `CHANGELOG.md`
  (new scaffolded dependency), and `todo.txt`.

### Phase 1 — Database schema and DAO

- [x] TDD: write `VideoDaoTest` first (insert video, add/replace paths, dedupe
  known path, `linkFrame`/`findFramesForVideo`, cascade deletes, frame-link
  uniqueness) over `Database.inMemory()` and watch it fail.
- [x] Extend `Database.initializeSchema()` with the `videos`, `video_paths` and
  `video_frames` DDL above (plus indexes on `video_frames(video_hash)`).
- [x] Implement `free.svoss.facesort.db.VideoDao`:
  `exists(hash)`, `findByHash(hash)`, `insert(...)`, `addPath(hash, path)`,
  `getPaths(hash)`, `hasPath`, `linkFrame(frameHash, videoHash, timestampMs)`,
  `findFramesForVideo(videoHash)`, `findTimestamp(frameHash)`,
  `updateVideoCounts(...)`, `deleteVideo(hash)`.
- [x] Run the new tests green (`mvn test`).
- [x] Docs per commit: `todo.txt`, `CHANGELOG.md` (as applicable), `README.md`
  (data storage table: document the new tables).

### Phase 2 — Frame extraction core

- [x] TDD: `VideoFrameSamplerTest` first — for short videos (<1 s for 0/1
  frame), exactly `duration` frames when `1 s ≤ duration ≤ 120 s`
  (one per second), exactly 120 frames when `duration > 120 s`, and in every
  case: every target in `[0, duration)`, ascending, spacing ≥ 1 s, at most the
  configured cap; then watch it fail.
  > Note: `max(1, floor(duration))` makes the minimum count 1, so any
  > video with positive duration yields exactly one target; the "0/1 frame"
  > wording in the plan is covered as "at least one, in range".
- [x] Implement `FrameSampler` (pure math):
  `count = min(MAX_FRAMES_PER_VIDEO, max(1, floor(duration)))`,
  `spacing = duration / count`, targets at `(i + 0.5) * spacing`. No ffmpeg
  dependency — fully unit-testable.
- [x] TDD: `VideoFrameUtilsTest` first — RGB24 byte[] → `BufferedImage` for
  small known images (corner/middle pixels), wrong-size input rejected; watch it
  fail.
- [x] Implement `VideoFrameUtils.rgb24ToImage(byte[], width, height)` and add a
  `hashPixels`-style helper if needed (or reuse `ImageUtils.toJpegBytes` +
  `HashUtils` for the frame hash).
  > Note: added `HashUtils.hashBytes(byte[])` for the frame hash (SHA-256 over
  > the full-frame JPEG bytes); `VideoFrameUtils` only converts RGB24 → image.
- [x] Define the **test seam**: an interface `VideoFrameSource` in
  `service` (opening a video, returning duration, and iterating the sampled
  frames) with a real `FfmpegVideoFrameSource` implementation wrapping the
  ffmpeg4j API, so `VideoImportService` is testable without native libs —
  mirroring the `FaceAiService.Engine` seam.
- [ ] TDD: `FfmpegFrameSource` behaviour covered via a **fake `VideoFrameSource`**
  in service tests (duration probe, forward-only seek, frame + timestamp
  delivery, EOF handling). Real-ffmpeg smoke tests optional/manual.
- [ ] Implement `FfmpegVideoFrameSource`: open `FFmpegIO.openInputStream(...)`,
  `registerStreams()`, pick `VideoSourceSubstream`, probe duration (format
  context, then package scan fallback), and for each ascending target:
  `setDecoding(false)` → `seek(target)` → `setDecoding(true)` →
  `videoSubstream.next()` → convert to `BufferedImage`; report the frame's real
  `getPosition()`. Close everything with try-with-resources / `AutoCloseable`.
- [ ] Run the new tests green (`mvn test`).
- [ ] Docs per commit: `todo.txt`, `CHANGELOG.md`, `README.md` as needed.

### Phase 3 — Video import service

- [ ] TDD: `VideoImportServiceTest` first (over `Database.inMemory()`,
  `FakeFaceAiEngine`, and a fake `VideoFrameSource` that yields a few identical
  frames):
  - imports a fake "video", stores video row + paths + one `images` row +
    `thumbnails` row + `video_frames` rows + `faces` rows per frame,
  - re-import of the same content/path is skipped; same content new path only
    adds a path,
  - frames without qualifying faces are still stored (frame thumbnail kept,
    `face_count` 0) and frames with faces get correct counts,
  - cancellation between videos keeps state consistent,
  - per-file errors are counted and do not abort the rest,
  - parallel multi-engine path works (mirrors `ImportServiceTest`).
  Watch them fail.
- [ ] **Refactor (shared pipeline)**: extract the per-image logic from
  `ImportService.processFile` that turns a loaded `BufferedImage` into stored
  `images`/`thumbnails`/`faces` rows (downscale-to-`maxDetectionDimension` +
  `mapToOriginal` + crop/downsize-to-160 + embedding + `sub_image_jpg`) into a
  shared, package-internal helper used by both `ImportService` and
  `VideoImportService`, keeping the detection criteria identical for photos and
  frames. Re-run the existing `ImportServiceTest` — must stay green.
- [ ] Implement `free.svoss.facesort.service.VideoImportService` (mirrors
  `ImportService`: `ImageDao` + `FaceDao` + new `VideoDao`, a list of
  `FaceAiService` workers, a `dbLock` serializing SQLite access, daemon worker
  pool, progress listener, cancellation supplier):
  - collect video files (recursive walk, extension filter),
  - per video: hash → known-video fast path (skip / add path) → probe duration →
    `FrameSampler` targets → `VideoFrameSource` frames → for each frame: frame
    hash (SHA-256 of the full-frame JPEG), insert `images` + `thumbnails` +
    `video_frames`, detect faces (see shared pipeline) → insert `faces`;
  - update `videos` (criteria JSON, counts, duration) on completion;
  - aggregate result record `VideoImportResult(totalVideos, newVideos, newFrames,
    newFaces, skipped, errors, processed, wasCancelled)`; `close()` releases the
    FaceAI services.
- [ ] Handle the frame-hash collision case: when a frame's content hash already
  exists in `images` (identical frame, or a photo with identical content), keep
  the existing image row and only add the `video_frames` link.
- [ ] Run full suite green (`mvn test`).
- [ ] Docs per commit: `todo.txt`, `CHANGELOG.md`, `README.md`.

### Phase 4 — Import tab integration and i18n

- [ ] Extend the Import tab folder scan so the "Import" run processes both image
  and video files: sequence `ImportService.importFolder(...)` then
  `VideoImportService.importFolder(...)` inside the same `Task`, streaming both
  to the same log area and stop flag.
- [ ] Update the folder chooser/labels so users know video files are included.
- [ ] Add i18n keys (English default + the six translations
  `messages_{de,fr,es,ru,zh}.properties`):
  - folder prompt mentions images **and videos**,
  - a combined summary line for videos (total / new / frames / faces / skipped /
    errors),
  - progress wording for the video phase.
- [ ] Verify cancellation crosses the image→video boundary cleanly and the final
  status shows both summaries.
- [ ] Manual QA with a local sample video: import, watch frames/faces appear in
  "Put a name to a face" / "Tag random face" / "Add faces to a name", restart
  the app, re-import the folder → videos skipped.
- [ ] Run full suite green (`mvn test`).
- [ ] Docs per commit: `todo.txt`, `CHANGELOG.md` (video import feature entry),
  `README.md` (Import tab description, "How to use" step 1).

### Phase 5 — Cross-feature behaviour (verify + adjust)

- [ ] TDD where behaviour changed: assert that a tagged video face appears in
  `ViewService.getImagesForName` with its frame thumbnail, and that
  `FaceToNameService.exportImagesForName` exports the frame **thumbnail** for
  video sources (no path → thumbnail fallback path).
- [ ] Verify (no code change expected): clustering, name-similarity ranking,
  deduplication and random/similar tagging all work unchanged because they are
  embedding-based.
- [ ] "Open Original" for a video face: confirm it reports "original not found"
  gracefully (no stored path). Document the limitation in `README.md`/tooltips;
  opening the video at the frame timestamp is future work.
- [ ] Path-prefix filters do not match video frames (Document in the tooltip /
  README). Optionally: extend the path filter to also consider `video_paths`
  (only if it stays small — otherwise leave as noted future work).
- [ ] Docs per commit: `todo.txt`, `CHANGELOG.md`, `README.md`.

### Phase 6 — CI and release validation

- [ ] Push through the full GitHub Actions matrix; confirm every platform jar
  builds, ffmpeg4j natives for other platforms are filtered out, and the fat jar
  still works.
- [ ] Smoke-test the shaded Windows jar locally: import a video, tag faces,
  restart and re-import.
- [ ] Final full `mvn test` green.
- [ ] Docs per commit: `todo.txt` (mark item 3 complete/removed),
  `CHANGELOG.md`, `README.md` + translated READMEs as applicable.

---

## 5. Files touched (overview)

| Area | Files |
|------|-------|
| Build / CI | `pom.xml`, `.github/workflows/build.yml` |
| DB | `db/Database.java`, new `db/VideoDao.java`, new `db/VideoDaoTest.java` |
| Extraction | new `service/FrameSampler.java`, `util/VideoFrameUtils.java`,
  `service/VideoFrameSource.java` (+ real `FfmpegVideoFrameSource`), tests |
| Import | new `service/VideoImportService.java`, refactor of
  `service/ImportService.java` (shared pipeline), `ui/ImportView.java` |
| i18n | `messages.properties` + all translations |
| Docs | `todo.txt`, `CHANGELOG.md`, `README.md` (+ translated READMEs) |

## 6. Known limitations / future work (out of scope now)

- "Open Original" on a video face opens nothing yet (could seek the video).
- Path-prefix filters (`ui.faceName.pathFilter` etc.) don't match video faces.
- Frame budget constants are not exposed in Settings.
- `ImageDao.getAllHashes()`/View/thumbnails now include frame-derived images;
  no UI distinguishes "photo" from "video frame" beyond the tooltip paths.