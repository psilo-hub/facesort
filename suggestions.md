# FaceSort — Improvement Suggestions

A codebase review of the FaceSort project (Java 17 / JavaFX 21, SQLite, HNSW, FaceAI,
ffmpeg). Findings are grouped by theme. Each item lists concrete file:line references,
an impact rating (High / Medium / Low), and a rough effort estimate.

All line numbers refer to the state of the codebase at the time of writing.

---

## 1. Thread-safety of the shared SQLite connection (High risk)

The entire app shares a single non-thread-safe SQLite `Connection` created in
`FaceSortApp.showMainWindow()` (`FaceSortApp.java:185-193`) and handed to all five
DAOs. Every UI view runs DAO-touching services in background `Task` threads, so reads
and writes can interleave from many threads at once.

- Import and video import serialize their own workers behind `dbLock`
  (`ImportService.java:75`, `VideoImportService.java:83`), but these are **two
  unrelated monitors** and nothing coordinates them with `ClusteringService`,
  `NamingService`, `ViewService`, `DeduplicationService`, or `FaceToNameService`,
  all of which also touch the connection from task threads.
- `ClusteringService.clusterUnnamed()` (`ClusteringService.java:79-100`) can run for
  a long time (loads all unnamed faces, builds an HNSW index) while import workers
  are writing to the same connection.

Suggested directions (pick one, in increasing effort):
1. A single shared `synchronized` guard around all DAO calls (a `SynchronizedConnection`
   proxy), so the locking model is uniform instead of per-service. **Low effort.**
2. `PRAGMA busy_timeout` + retry so transient `SQLITE_BUSY` becomes rare instead of a
   user-visible failure. **Low effort.**
3. One connection per worker thread (per import thread / per task), with the executor
   owning connection lifecycle. **Medium effort** but eliminates the whole class of
   problems and enables true parallelism on imports.

---

## 2. UI stale-task race — `activeTask.getValue()` in handlers (High risk)

In the four `Refreshable` views and their close relatives, every
`setOnSucceeded`/`setOnFailed` handler reads the **shared mutable field** `activeTask`
instead of capturing the task it is attached to. Example: `ViewView.java:117`
`(List<NameSummary>) activeTask.getValue()`. There are ~14 such sites
(`ViewView.java:117,174,365`, `FaceNameView.java:205-206,270-271`,
`NameFaceView.java:139-141,227-229,272-273`, `RandomNameView.java:136-137,183-184`).

If task A finishes and its success notification is queued, but task B is started
before the FX thread runs A's handler, the handler reads B's value — wrong data or a
`ClassCastException` (hidden behind `@SuppressWarnings("unchecked")` everywhere).
Only `NameFaceView` (`candidateRepId`, `NameFaceView.java:230-238`) and the two
`checkNameExists` methods guard against stale results today.

Fix: bind handlers to the **local** task instance (`task.setOnSucceeded(...)` and
read `task.getValue()`), optionally with a generation counter as the staleness guard.
A shared `TaskRunner` helper in the ui package would fix all sites at once.

---

## 3. Duplication (High effort payoff, mostly mechanical)

### 3a. Import vs. video import orchestration
`ImportService` and `VideoImportService` duplicate almost verbatim:
- Worker thread pool creation, `runWorker`, `awaitWorkerCompletion`, progress reporting
  (`ImportService.java:141-262` vs `VideoImportService.java:163-283`).
- File collection via `SimpleFileVisitor` + extension predicate
  (`ImportService.java:407-435` vs `VideoImportService.java:402-442`).
- Thumbnail encoding (`encodeThumbnail`, `ImportService.java:389-393` vs
  `VideoImportService.java:384-388`), plus `JPEG_QUALITY = 0.85f` duplicated a third
  time in `FaceDetectionUtils.SUB_IMAGE_JPEG_QUALITY` (`FaceDetectionUtils.java:35`).

Extract a shared `ImportWorkerPool<T>` (or helper) and a `Thumbnailer` utility.

### 3b. UI copy-paste across the five face-related views
- `checkNameExists` is duplicated verbatim (`NameFaceView.java:249-289`,
  `RandomNameView.java:160-200`), including the same green/red hex colors.
- `installPathTooltip` is identical (`NameFaceView.java:453-472`,
  `RandomNameView.java:465-484`).
- The `isOriginalAvailable` / `isContainingFolderAvailable` / `openOriginal` /
  `openContainingFolder` quartet is repeated across **five** views
  (`DedupeView.java:364-420`, `ViewView.java:292-346`, `FaceNameView.java:493-547`,
  `NameFaceView.java:501-555`, `RandomNameView.java:310-364`), along with the
  near-identical context-menu builders and a `handleFailure`/`showError` Alert
  (`ViewView.java:441-452`, `FaceNameView.java:839-850`, `NameFaceView.java:601-612`,
  `RandomNameView.java:530-541`, `DedupeView.java:428-438`).
- `setTask`/`startTask` (4×), `setImage(ImageView, FaceRecord)` (3× +
  `ViewView.thumbnail`), the candidate-card `VBox` render loop (3×), and the
  `tooltip(String)` helper (2×).

These are exactly the responsibilities of the two currently-unused components
`FaceThumbnail` (`ui/components/FaceThumbnail.java`) and `ProgressDialog`
(`ui/components/ProgressDialog.java`) — wire them in rather than deleting them, or
create one `UiUtils`/`FacesTabs` helper to absorb the four-method cluster.

### 3c. Service-level duplication
- `createOrFindName` / `findName` duplicated in `NamingService.java:239-263` and
  `FaceToNameService.java:366-392` — extract a `NameService`.
- "Pick representative = face closest to average embedding" is reimplemented in
  `ClusteringService.java:226-240`, `DeduplicationService.java:254-282`,
  `ViewService.java:337-351`, and `FaceToNameService.java:246-257` — extract an
  `EmbeddingMath`/`FaceSelector` utility.
- `FaceDao` column list string duplicated 6× (`FaceDao.java:68-69,103-104,150-151,
  168-169,186-187,200-201`); `NameDao` correlated `face_count` subquery + row mapping
  duplicated 3× (`NameDao.java:45-46,61-62,78-81`).
- Video extension knowledge is split: `VideoImportService.SUPPORTED_EXTENSIONS`
  (`VideoImportService.java:66-69`) vs `FfmpegVideoFrameSource.demuxerName`
  (`FfmpegVideoFrameSource.java:163-180`) — a single source of truth would prevent drift.

---

## 4. Transactions (Medium risk)

There are **no transactions** anywhere (`setAutoCommit`/`commit`/`rollback` have no
calls in main code). Multi-statement write units can leave partial state on failure:

- Image import commit unit (`ImportService.java:311-334`): `insert` image + path +
  N face inserts are auto-commit; a face-insert failure leaves an orphan image row
  with a `face_count` that no longer matches.
- Video frame write unit (`VideoImportService.java:347-367`): per-frame inserts;
  if the loop throws before the final recount, the video row keeps stale counts.
- `DeduplicationService.merge()` (`DeduplicationService.java:124-131`): `reassignAll`
  then `delete`; a failure between them leaves an inconsistent state.

Adding `BEGIN`/`COMMIT` (or SQLite savepoints) around these units is the fix.

---

## 5. Heavy work performed while holding `dbLock` (Medium)

JPEG encoding is done **inside** the synchronized DB lock, stalling every parallel
import worker:
- Image collision path re-encodes the thumbnail under the lock
  (`ImportService.java:325-327`).
- Video import downscales + encodes each full-resolution frame under the lock
  (`VideoImportService.java:353`).

Move encoding outside the lock (encode first, then synchronize only the writes).

---

## 6. Dead code, dead schema, unused public API (Low risk, easy cleanup)

Confirmed unused (no production call sites):

| Location | What |
|---|---|
| `ui/components/FaceThumbnail.java` | Entire component unused (also its "synchronously decodes" javadoc is wrong — `Image(InputStream)` loads in the background) |
| `ui/components/ProgressDialog.java` | Entire component unused |
| `ImageDao.delete(String)` (`ImageDao.java:106-112`) | No call sites anywhere |
| `VideoDao.delete(String)` (`VideoDao.java:212-218`) | No call sites anywhere |
| `NameRecord.withFaceCount` (`NameRecord.java:66-68`) | No call sites |
| `FaceRecord.faceIndex` / `faceIndex()` / `boundingBox()`, 11-arg ctor | Never read (mapped as hard-coded -1, `FaceDao.java:333`) |
| `ImageRecord` 4-arg ctor / `thumbnailPath()` / `hasFaces()` | Never used in main code |
| `EmbeddingUtils.cosineSimilarity` (`EmbeddingUtils.java:67-88`) | Tests/fakes only |
| `ImageUtils.crop` (`ImageUtils.java:76-96`) | Tests only |
| DB columns `video_frames.face_count` (`Database.java:140`) | Never written, never read |
| DB columns `videos.frame_count`/`face_count` | Written by `VideoDao.updateVideoCounts` (`VideoDao.java:199-207`) but **never read** — `findByHash` recomputes live subqueries (`VideoDao.java:60-83`); stored values can silently drift |

Either delete the dead code/columns or wire them to a real feature (e.g. a delete-image/
delete-video UI would hit two of these).

Also: `not_dupes` has **no model record** — `NotDupeDao` returns mutable `long[]`
pairs (`NotDupeDao.java:59-60,73-74`). An immutable `NamePair(long a, long b)`
record would match the rest of the model package.

---

## 7. Bugs & small UX issues (Low effort, high polish)

- **Lost "Saved" confirm on language change**: `onLanguageChanged.run()` (rebuilds the
  whole window) runs **before** `statusLabel.setText(I18n.get("settings.saved"))`
  (`SettingsView.java:267-270`), so the label belongs to the replaced view and the
  confirmation is never visible.
- **Indeterminate progress sentinel**: `progressBar.setProgress(-1)`
  (`ImportView.java:178`) vs the named constant `ProgressBar.INDETERMINATE_PROGRESS`
  used in `ModelDownloadView.java:162`.
- **Inconsistent number formatting**: identical similarity labels render `42%`
  (`String.format(Locale.ROOT, ...)`, `FaceNameView.java:342`, `NameFaceView.java:363`)
  in one place and `42,0%` (via `I18n.format`, `I18n.java:62-64`) in another. Standardize
  on one policy.
- **`FaceDao` implicit placeholder contract**: the path filter hard-codes 5
  placeholders (`FaceDao.java:285-318`) and `findRandomUnnamed` sets `ps.setInt(6, limit)`
  (`FaceDao.java:153`); the contract is only a Javadoc. This breaks silently if the
  clause changes. `ORDER BY RANDOM()` (`FaceDao.java:151`) also scans + sorts the whole
  table.
- **`FrameSampler.MIN_FRAME_MS_SPACING = 1000`** (`FrameSampler.java:20`) is declared
  and never used — dead or misleading.
- **`FaceDetectionUtils` swallows per-face errors** (`FaceDetectionUtils.java:88-92`):
  logged but never propagated to the file-level error counters, so corrupt face crops
  are invisible to users.
- **Inline hex colors** (`#4a90d9`, `#c9302c`, `#3c763d`, ...) duplicated across
  `setStyle` calls (24+) while `styles.css` already defines matching-but-unused classes
  (`.name-label`, `.count-label`, `.name-card`, ...). Move presentation to CSS.
- **Hardcoded config dir**: `Path.of("config")` spelled 3 ways (`FaceSortApp.java:109`,
  `FaceSortApp.java:182`, `ImportView.java:247`).
- **Logging**: `UpdateChecker` and `UpdateNoticeDialog` use
  `System.err.println`/`printStackTrace` (`UpdateChecker.java:81,94,191`,
  `UpdateNoticeDialog.java:50,53`) instead of `java.util.logging`.
- Doc mismatch: `FaceRecord.java:63-66` claims `FaceDao.insert` throws `SQLException`
  for a null sub-image, but `FaceDao.insert` (`FaceDao.java:31-34`) throws
  `IllegalArgumentException`.

---

## 8. Configuration, schema evolution & resource lifecycle (Medium)

- **No schema versioning**: all DDL is `CREATE TABLE IF NOT EXISTS` (`Database.java:
  52-149`), with no `PRAGMA user_version` and no way to `ALTER` an existing table. The
  schema cannot evolve non-additively. Consider a tiny versioned-migration helper.
- **No `ConfigModel` validation on load**: hand-edited config can carry
  `minConfidence = -1`, `hnswM = 0`, etc., which surface as library errors at runtime
  (`ClusteringService.java:127-132`). Normalize/clamp after deserialization.
- **HNSW index never closed**: `ClusteringService` builds an `HnswIndex` locally
  (`ClusteringService.java:125-137`) and never releases it — a native/heap memory
  leak if clustering runs repeatedly.
- **Un-timed network calls**: `UpdateChecker` fetches a raw GitHub URL with no timeout
  (`UpdateChecker.java:79`) and `FeedbackService` (`FeedbackService.java:62-88`) has no
  explicit request timeout; add timeouts. Non-atomic cache write at
  `UpdateChecker.java:87` (truncation risk).
- **Hardcoded integrations**: `FeedbackService.ACCESS_KEY` (`FeedbackService.java:30`)
  and `SUBMIT_URL`; `UpdateChecker.RELEASES_URL` + 2-day `CHECK_INTERVAL`
  (`UpdateChecker.java:35`). The access key is a public form key (safe by design) but
  could live in config for easier rotation.
- **`FaceAiService.DEVICE = "CPU"`** (`FaceAiService.java:28`): GPU users get no choice;
  this is a natural config field.

---

## 9. Test suite (Medium)

Strengths: broad coverage (28 test classes), good seam design (`FaceAiService.Engine`,
`VideoFrameSourceOpener`), per-DAO tests, and `InterfaceBetweenServices`-style fakes.
Areas worth attention:

- **Timing-sensitive assertion**: `ImportServiceTest` asserts `elapsedMs < 1500`
  (`ImportServiceTest.java:633`) next to `Thread.sleep(40)` and barrier-based fakes —
  flaky on slow CI.
- **Weak assertions**: `NamingServiceTest.findSimilarUnnamed_ranksMostSimilarFirst`
  (`NamingServiceTest.java:210`) checks size only, not order; `rankSimilar_honorsLimit`
  similarly weak.
- **Real-video tests skip silently**: `FfmpegEndToEndImportTest` uses `assumeTrue`
  (`:55`, `:94`) and depends on GitHub-hosted sample files
  (`ffmpeg4j-deps/.../sample-mp4-file-small.mp4`, `test_media/...`) — on offline/CI
  machines they silently pass without testing anything. Bundle a tiny sample video.
- **Fakes duplicated**: `VideoImportServiceTest.FakeImportFrameSource` +
  (local) `CountingEngine` vs the shared `FakeFaceAiEngine`
  (`FakeFaceAiEngine.java`) — consolidate.
- **No tests for**: `I18n` (static, process-global), `FaceDetectionUtils` error paths,
  `UpdateChecker` runtime path (network/cache), `FaceAiService.toFaceAIConfig`,
  and the DB layer's FK-cascade/`delete` behaviors (e.g. `NotDupeDao.deleteForName` is
  test-only because production relies on the FK cascade — assert that cascade).
- **No Mockito**: only hand-rolled fakes. Fine, but the union of hand-rolled fakes is
  itself a maintenance surface worth reviewing.

---

## 10. Documentation sync (Low effort)

Confirmed gaps:

- **Translated READMEs lack video support entirely**: `README.de.md`, `README.es.md`,
  `README.fr.md`, `README.ru.md`, `README.zh.md` have **zero** mentions of "video"
  (English site documents it). Each needs the video-import section added.
- **`todo.txt`/`CHANGELOG.md` are stale**: todo.txt claims "Final full suite green
  235/235" and "run 44" (suite is now 255) and contains the "tagging facces" typo;
  `target/surefire-reports/` timestamps (2026-09-19) pre-date the 7 newer test classes
  (stale artifacts in the repo — recommend adding `target/` to `.gitignore` if not
  already ignored).
- **AGENTS.md doc-sync rule** ("update todo.txt, CHANGELOG.md, README.md in the same
  commit") lists only the English README; the five translated READMEs are also part of
  the deliverable and can drift out of sync.
- `README.md:167` claims the update-cache behavior accurately, but the coupling to the
  exact `CHANGELOG.md` heading regex (`UpdateChecker.java:40`) is undocumented — worth a
  note if a future release changes the changelog format.

---

## 11. Feature ideas (unordered)

- **GPU inference option**: expose `FaceAiService.DEVICE` in Settings; would speed up
  clustering/import on CUDA machines.
- **Configurable import budgets**: thumbnail JPEG quality (0.85f), max frames per video
  (`FrameSampler.MAX_FRAMES_PER_VIDEO = 120`), face-crop size (160) — move to
  `ConfigModel`.
- **Delete image/video feature**: `ImageDao.delete` and `VideoDao.delete` already exist
  with zero call sites; a UI would justify them and remove ambiguity about drift.
- **Per-name search/filter** in the browse tab (currently `ViewView` lists all names;
  a text filter + count badge would scale past hundreds of names).
- **Schema migration on startup** with `PRAGMA user_version` (enables future
  non-additive changes).
- **Failures surfaced, not swallowed**: per-face detection errors (`FaceDetectionUtils:
  88-92`) could aggregate into the import log/summary instead of the console.
- **Release-attached auto-update**: switch `UpdateChecker` from parsing `CHANGELOG.md`
  to querying the GitHub Releases API, which already produces release notes in CI
  (`.github/workflows/build.yml:110-113`).
- **Build caching in CI**: the workflow re-clones and rebuilds three external
  dependencies on every run; a Maven local-repo cache (actions/cache on `~/.m2`) would
  cut minutes per job.

---

*Generated from a codebase review; no code changes accompany this document.*