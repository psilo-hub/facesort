# FaceSort — Improvement Suggestions

A codebase review of the FaceSort project (Java 17 / JavaFX 21, SQLite, HNSW, FaceAI,
ffmpeg). Findings are grouped by theme. Each item lists concrete file:line references,
an impact rating (High / Medium / Low), and a rough effort estimate.

All line numbers refer to the state of the codebase at the time of writing.

---

## ~~1. Thread-safety of the shared SQLite connection (High risk)~~ ✅ DONE

**Solved 2026-09-22** — `Database.getConnection()` now returns a
`SynchronizedConnection` proxy (`db/SynchronizedConnection.java`) that
serializes every DAO call behind a single monitor; all service/worker threads
share that one lock, and the per-service `dbLock` monitors were removed. See
todo.txt item 7.

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

## ~~2. UI stale-task race — `activeTask.getValue()` in handlers~~ ✅ DONE

**Solved 2026-09-22** — all five views (View, FaceName, NameFace, RandomName,
Dedupe) now bind success/failure handlers to the **local** task instance they
start and read that instance's value, instead of the shared `activeTask` field.
A shared `ui/TaskRunner.java` helper centralizes "cancel the previous task +
start on a daemon thread" and returns the started task; the per-view
`activeTask`/`setTask`/`startTask` plumbing was removed. The existing staleness
guards (`checkNameExists` name-equality, `NameFaceView.candidateRepId`,
`DedupeView.sessionActive`) are preserved. A handler can no longer observe a
newer task's result once an older task finishes late.

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

### ~~3a. Import vs. video import orchestration~~ ✅ DONE

**Solved 2026-09-23** — new shared `service/` helpers used by both imports:
`ImportWorkerPool` (fixed pool of daemon worker threads claiming files off a
shared counter, "Started/Completed i/N" progress, cancellation, exception and
dropped-face error counting, and the unbounded join so `importFolder` never
returns while a worker is still running), `ImportFiles` (the recursive
`walkFileTree` collection + extension predicate, previously duplicated line for
line), and `Thumbnailer` (downsize + JPEG encode; its `JPEG_QUALITY` is now the
single 0.85f constant also used by `FaceDetectionUtils` for face sub-images).
Each service keeps only its per-file `processFile`/`processVideo` logic. Covered
by the new `ImportWorkerPoolTest` and `ImportFilesTest`; full suite green.

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

## ~~4. Transactions (Medium risk)~~ ✅ DONE

**Solved 2026-09-23** — new `db/TransactionRunner` (exposed as
`Database.getTransactionRunner()`) runs `TransactionUnit`s as real
BEGIN/COMMIT transactions, holding the `SynchronizedConnection` monitor for the
whole unit so an open transaction can never absorb statements from other
workers. The three write units named below now commit atomically and roll back
as a whole on any SQLException or runtime failure (covered by
`db/TransactionRunnerTest`).

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

## ~~5. Heavy work performed while holding `dbLock`~~ ✅ DONE

**Solved 2026-09-22** — the per-service `dbLock` monitors no longer exist (removed
by the `SynchronizedConnection` refactor, see item 1). Database access is now
serialized per DAO call, so JPEG encoding never runs under a lock: thumbnail,
crop, downscale and full-frame encoding all happen in `FaceDetectionUtils` and
the import services' `encodeThumbnail` helpers, i.e. between individually
synchronized DAO calls. The DAO layer itself performs no image work.

JPEG encoding is done **inside** the synchronized DB lock, stalling every parallel
import worker:
- Image collision path re-encodes the thumbnail under the lock
  (`ImportService.java:325-327`).
- Video import downscales + encodes each full-resolution frame under the lock
  (`VideoImportService.java:353`).

Move encoding outside the lock (encode first, then synchronize only the writes).

---

## ~~6. Dead code, dead schema, unused public API (Low risk, easy cleanup)~~ ✅ DONE

**Solved 2026-09-23** — see todo.txt item 13 for details.

Chosen direction (User decision, 2026-09-23): **delete** rather than wire in.
Summary of each row:

- `ui/components/FaceThumbnail.java` and `ui/components/ProgressDialog.java` were
  **deleted** (component "wire them in" option declined; they can be re-created
  when item 3b absorbs the view duplication). The `face-thumbnail` /
  `face-thumbnail.selected` CSS rules went with them.
- `ImageDao.delete(String)` / `VideoDao.delete(String)` **deleted** (the
  "add a delete-image/delete-video UI" option declined). The FK cascade they
  were tested through remains in place; those cascade-only tests were removed
  with the methods.
- `NameRecord.withFaceCount`, `FaceRecord.faceIndex`/`faceIndex()`/`boundingBox()`
  including the 11-arg and `Rectangle2D` convenience ctors, and `ImageRecord`'s
  UI-facing ctor / `thumbnailPath()` / `hasFaces()` — all **deleted**.
- `EmbeddingUtils.cosineSimilarity` **deleted** from the shipped code; the math
  now lives once as a package-private helper on the shared `FakeFaceAiEngine`
  test seam (rewiring the fakes/tests that used it).
- `ImageUtils.crop` **deleted** (it was never exercised by production code).
- DB columns `video_frames.face_count` and `videos.frame_count`/`face_count`:
  **dropped via a schema migration**. `SCHEMA_VERSION` bumped to 2;
  `Database.migrate` drops the columns with a `PRAGMA table_info` presence guard
  so brand-new databases are unaffected, and `VideoDao.updateVideoCounts` plus
  its `VideoImportService` call were removed (counts were written but never
  read — `findByHash` computes them live). Covered by
  `databaseFromVersionOne_dropsDeadCountColumns` in `DatabaseTest`.
- `not_dupes` now has a model: immutable `NamePair(long a, long b)` record
  (normalizes so `a <= b`, matching `NotDupeDao` storage), returned by
  `NotDupeDao.findByNameId`/`findAll` instead of mutable `long[]` pairs.

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

## ~~7. Bugs & small UX issues (Low effort, high polish)~~ ✅ DONE

**Solved 2026-09-23** — all bugs below except two explicitly deferred items:

- Lost "Saved" confirm fixed: the language-switch callback now returns the freshly
  built `SettingsView` (`Supplier<SettingsView>`), and the status text ("Saved" or the
  save-failure message) is shown on that rebuilt view's label instead of the detached
  one (`SettingsView.java`, `FaceSortApp.java`).
- Import progress uses the named `ProgressBar.INDETERMINATE_PROGRESS` constant instead
  of a literal `-1` (`ImportView.java:178`).
- Similarity percentages standardized on `I18n.percent(double)` (active locale, rounded
  to a whole percent) in `FaceNameView` and `NameFaceView`; covered by a new `I18nTest`.
- `FaceDao`'s implicit 5-placeholder path-filter contract is now the named constant
  `FaceDao.PATH_FILTER_PLACEHOLDERS`, and the `LIMIT ?` in `findRandomUnnamed` binds at
  `1 + PATH_FILTER_PLACEHOLDERS` instead of magic index 6.
- `FrameSampler.MIN_FRAME_MS_SPACING` is now actually used by `countFrames` (the
  per-second budget is derived from it) instead of being a dead constant.
- `FaceDetectionUtils` no longer swallows per-face errors: `detectFaces` returns a
  `DetectionResult(faces, droppedFaces)`, and photo/video import count a file with any
  dropped face as an error while still persisting the remaining faces (and the file).
  Covered by new tests in `ImportServiceTest` and `VideoImportServiceTest`.
- `UpdateChecker` and `UpdateNoticeDialog` use `java.util.logging` instead of
  `System.err`/`printStackTrace`.
- `FaceRecord.java:65` javadoc corrected (`IllegalArgumentException`, not `SQLException`),
  and `AppConfig.DEFAULT_CONFIG_DIR` is the single source for the `config` directory
  (`FaceSortApp.java:109,182`).

Deferred intentionally (not part of this pass): the `ORDER BY RANDOM()` scan
(`FaceDao.java:151` — the path filter restricts via EXISTS subqueries, so offset-based
sampling would make the query far more complex for little gain) and the inline-hex → CSS
migration (24+ `setStyle` sites; a pure-presentation change with no functional benefit).

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

## ~~8. Configuration, schema evolution & resource lifecycle (Medium)~~ ✅ DONE

**Solved 2026-09-23** — version migrations, clamp-on-load, network timeouts and an
atomic cache write landed as one round. The HNSW-close concern was dismissed with
evidence and two integrations were deferred deliberately (see below). See todo.txt
item 12.

Original findings, with the resolution of each:

- **No schema versioning** ✅ FIXED: `Database.initializeSchema()` now reads
  `PRAGMA user_version`; unversioned/older databases (version 0) are migrated by
  the idempotent baseline DDL and stamped with the new `Database.SCHEMA_VERSION`
  (currently 1). A `migrate(stmt, fromVersion)` seam is in place for future
  non-additive changes, and opening a database stamped by a *newer* build is
  tolerated (logged as a warning, nothing downgraded or dropped). Covered by the
  new `db/DatabaseTest` (fresh stamping, tables/indexes present, idempotent
  reopen that keeps data, unversioned legacy DB migration, newer-version
  tolerance).
- **No `ConfigModel` validation on load** ✅ FIXED: `ConfigModel.normalize()` is
  invoked from `AppConfig.load` right after deserialization and clamps
  hand-edited config into usable ranges: ratios/similarities
  (`minConfidence`, `clusteringThreshold`, `minNameSimilarity`) to [0,1], counts
  and HNSW parameters to documented minimums (`minBoundingBoxSize`,
  `maxFacesPerImage`, `maxDetectionDimension`, `thumbnailSize`, `hnswM`,
  `hnswEfConstruction`, `hnswEfSearch`, `knnK`, `faceNameMaxImages`), and
  `maxImportThreads` to [1, `MAX_IMPORT_THREADS`]. Covered by `ConfigModelTest`
  and an `AppConfigTest` load-clamp case.
- **HNSW index never closed** ⏭️ DISMISSED (with evidence): the project's
  hnswlib is the pure-Java `hnswlib-core` (no JNI/native heap), and its public
  `HnswIndex` implements only `Index` — there is no `close()` to call. The index
  built in `ClusteringService.buildIndex` is a method-local that becomes
  GC-eligible as soon as `clusterUnnamed` returns, so repeated runs leak nothing.
- **Un-timed network calls** ✅ FIXED: `FeedbackService` sets a bounded request
  timeout (default 15s, `FeedbackService.REQUEST_TIMEOUT`, injectable for tests;
  new test proves a server that never answers surfaces as a timeout instead of a
  hang). `UpdateChecker` fetches through a package-private `RemoteFetcher` seam,
  bounded by a 20s timeout on a daemon thread so a stalled network can neither
  block startup nor the JVM exit. The changelog cache is now written atomically
  (temp file + `ATOMIC_MOVE`, fallback to plain move) so an interrupted write
  can no longer leave a truncated cache. Covered by seven new
  `UpdateCheckerTest` cases driven through the seam.
- **Hardcoded integrations** ⏭️ DEFERRED: `FeedbackService.ACCESS_KEY`/
  `SUBMIT_URL` and `UpdateChecker`'s URL/`CHECK_INTERVAL` stay in code for now.
  The access key is a public client-side form key (safe by design), and rotating
  it would need either a config UI or a documented/config-validated JSON key —
  neither pays off for a public key. Keeping it in code also preserves the
  bundled-by-default behavior. Revisit only if key rotation ever becomes a real
  workflow.
- **`FaceAiService.DEVICE = "CPU"`** ⏭️ DEFERRED: the current `faceai` snapshot
  does **not** consume the `device` setting — its DJL `Criteria` builders
  (`RetinaFaceDetector.buildCriteria`, `FaceNetRecognizer.buildCriteria`) neither
  set `optDevice` nor reference the config device. Exposing a device option now
  would be a user-visible no-op. The right move is to add device support to the
  `faceai` library first, then surface an app config field wired through
  `FaceAiService.toFaceAIConfig`.

---

## ~~9. Test suite (Medium)~~ ✅ DONE

**Solved 2026-09-23** — see todo.txt item 14 for details. Resolution of each point:

- **Timing-sensitive assertion** ✅ FIXED: `ImportServiceTest.importFolder_returnsOnlyAfterAllWorkersFinished`
  no longer measures wall-clock time. Instead each `SleepEngine` bumps shared
  `active`/`maxActive` counters, and the test asserts the workers really overlapped
  (`maxActive == 2`) and that `importFolder` joined every worker before returning
  (`active == 0` after the call) — deterministic on any machine.
- **Weak assertions** ✅ FIXED: `findSimilarUnnamed_ranksMostSimilarFirst` now uses
  distinct embeddings (identical vs orthogonal) and asserts the most-similar face is
  the first result and the least similar is last; `rankSimilar_honorsLimit` asserts
  that a limit of 1 returns the exact most-similar candidate.
- **Real-video tests skip silently** ✅ FIXED: a new pure-Java `TinyAviVideo` helper
  (test service package) writes a real 1 fps Motion-JPEG AVI (RIFF layout with
  ffmpeg MKTAG-ordered fourccs) on the fly, so `FfmpegEndToEndImportTest` runs the
  genuine native ffmpeg decode path in every environment and never uses
  `assumeTrue`. The "unreadable header" failure mode is covered by a garbage file
  that must surface as a clean `IOException`; the `AV_PIX_FMT_NONE` guard stays as
  defensive code.
- **Fakes duplicated** ✅ FIXED: `ImportServiceTest`'s and `VideoImportServiceTest`'s
  local `CountingEngine`s now extend the shared `FakeFaceAiEngine` (which gained a
  `detectCalls()` counter). The two *frame-source* fakes (`FakeVideoFrameSource`,
  `FakeImportFrameSource`) are intentionally kept — they emulate frame delivery, a
  different contract than the FaceAI engine seam.
- **No tests for**: ✅ COVERED — `I18n` (`I18nTest`) and `UpdateChecker` runtime/cache
  (`UpdateCheckerTest`) already existed and were verified present; this round adds
  `FaceAiServiceTest` (`toFaceAIConfig` mapping incl. blank-cacheDir fallback, null
  handling, argument contracts), `FaceDetectionUtilsTest` (criteria filtering,
  max-faces cap, dropped-face counting on embedding failure, detection-scale mapping
  back to original coordinates, criteria JSON), and DB FK-cascade tests
  (`NameDaoTest.delete_setsFaceNameIdToNullWithoutDeletingFaces`;
  `DatabaseTest` image/video cascades covering both FK directions).
- **No Mockito**: kept as-is — the union of hand-rolled fakes was reduced by the
  `CountingEngine` consolidation and reflects the existing seam design.

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