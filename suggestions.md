# FaceSort — Improvement Todo List

Checklist of open improvement suggestions for the codebase. Every item is a
`- [ ]` box: tick it to `- [x]` when the work lands, and move the item's
resolution into `todo.txt` + `CHANGELOG.md` in the same commit (see `AGENTS.md`).

All line numbers refer to the current state of the codebase
(2026-09-28, `mvn test` green: **373 tests / 43 classes, 0 failures, 0 skipped**).

Items are ordered roughly by payoff. Sections 1–3 are correctness/robustness and
should be done first; sections 4–9 are clean-up, process, UX and features.

---

## 1. Correctness bugs

- [x] **ffmpeg native resources leak on every unreadable video** —
  `Session.open` only catches `FFmpegException` and `RuntimeException`
  (`FfmpegVideoFrameSource.java:239-247`), but `checkVideoDecodable` throws
  `IOException` (`:179-188`). On the most common failure path (corrupt/mislabelled
  file) that `IOException` escapes **without** the `closeQuietly(sourceStream)` /
  `input` / `io` cleanup the other two catches perform, leaking native
  AVIO + AVFormatContext allocations. Catch `IOException` too, or close in a
  `finally` whatever was opened. **High payoff, small.**
  *Done — the cleanup moved into a `finally` guarded by an `opened` flag, so every
  failure path (the `checkVideoDecodable` `IOException`, `FFmpegException`,
  `RuntimeException`, `Error`) releases the chain in reverse creation order while
  a successful open keeps it for the `Session`. Pinned by the new
  `FfmpegVideoFrameSourceTest` (3 cases), which asserts on
  `FFmpegIO.getStatesInUse()`: 3 rejected videos left 3 live native I/O states
  before the fix, 0 after. Also removed the now-redundant `RuntimeException`
  catch, and `TinyAviVideo.writeUndecodable` builds the triggering file (an AVI
  whose `strf` BITMAPINFOHEADER is blank) in pure Java.*
- [ ] **A failed video import permanently poisons the video** —
  `videoDao.insert` + `addPath` are committed *before* the frame loop
  (`VideoImportService.java:220-223`) so frame links can resolve their FK. If any
  frame then fails, the video row survives with zero frames, and on re-import the
  "already imported" short-circuit skips it forever — silently, with no error and
  no recovery. Either wrap the insert in the same transaction unit as the frames
  or delete the row in a compensating step on failure. **High payoff.**
- [ ] **`AppConfig.save` is not atomic** — `MAPPER.writeValue(File, …)`
  (`AppConfig.java:63-69`) truncates the target and streams into it. A crash
  mid-write leaves a truncated `facesort-config.json`, and the next launch throws
  in `load` — the app refuses to start over the user's only copy of their
  settings. The correct pattern already exists in this codebase:
  `UpdateChecker.writeCacheAtomically` (temp file + `ATOMIC_MOVE`). **High payoff, small.**
- [ ] **`AppConfig.load` hard-fails on an unknown property** — the shared
  `ObjectMapper` (`AppConfig.java:25-26`) leaves `FAIL_ON_UNKNOWN_PROPERTIES`
  enabled. A hand-edited, stale or renamed key makes the app refuse to launch.
  For a user-editable settings file this is a poor failure mode, and `AGENTS.md`
  requires a documented migration for config changes. Disable the flag
  (`@JsonIgnoreProperties(ignoreUnknown = true)`) and pin it with a test.
  **High payoff, small.**
- [ ] **Re-exporting a person to the same folder aborts** — `Files.copy` is called
  without `StandardCopyOption.REPLACE_EXISTING` (`FaceToNameService.java:511`),
  and `uniqueDestination` only guards against names used *within this run*, so a
  second export derives the identical destination and throws
  `FileAlreadyExistsException`. The thumbnail branch two lines down uses
  `Files.write`, which *does* overwrite — the two branches behave inconsistently.
  **Medium payoff, small.**
- [ ] **`ImportService` swallows unrelated database failures** — the collision
  handler catches a bare `SQLException` and, whenever any row for that hash
  exists, reinterprets *any* error (disk full, `SQLITE_CORRUPT`, a constraint
  violation) as "another worker imported this first" and reports success
  (`ImportService.java:225-239`). Narrow it to
  `SQLIntegrityConstraintViolationException` / `SQLTransactionRollbackException`.
  **Medium payoff, small.**
- [ ] **`RemoveByPrefixDialog` can become permanently unusable** — in `onScan`, if
  the estimate is `null` the success handler returns *before* `setBusy(false)`
  (`RemoveByPrefixDialog.java:120-125`), leaving the dialog disabled with no way
  back. Move `setBusy(false)` above the null check (or use `finally`).
  **High payoff, one line.** Needs a headless test seam to pin the null path.
- [ ] **`ViewView.untagFaces` reads a mutable field off the background thread** —
  the mutable `activeNameId` is read inside `call()` and again in the success
  handler (`ViewView.java:359-372`), so changing the selected person mid-flight
  untags and refreshes the wrong one. The same defect exists at
  `FaceNameView.java:582-588` (`activeName`). Both should capture a `final` local
  before creating the task — the pattern already used at `RandomNameView.java:309`
  and `FaceNameView.java:673`. **High payoff, small.**
- [ ] **`FaceSortApp.stop()` can leak the database handle** — the three `close()`
  calls are unguarded (`FaceSortApp.java:350-363`). `importService.close()` and
  `faceAiService.close()` both release native resources and both rethrow the
  first failure, so if either throws, `database.close()` never runs. Give each its
  own try/catch, suppress the rest, close the database in a `finally`. The same
  gap exists in the import-worker construction loop (`FaceSortApp.java:213-216`):
  if the 9th `FaceAiService` fails, the 8 already-built ones leak. **Medium payoff, small.**
- [ ] **Null-safety gaps in the two seams that face native code** —
  `FaceDetectionUtils` consumes `service.detectFaces(...)` without a null check
  (`FaceDetectionUtils.java:63`), and `VideoFrameSource.SampledFrame`
  (`VideoFrameSource.java:54`) accepts null components although every caller
  dereferences it immediately (`VideoImportService.java:226-231`). `FaceRecord`
  already does the right thing with `requireNonNull` (`FaceRecord.java:36`) —
  apply the same discipline here. **Medium payoff, small.**

---

## 2. Concurrency & threading

- [ ] **`ConfigModel` is an unsafe-publication hazard** — one mutable bean is
  shared by the FX thread (which mutates it in `SettingsView.onReset`,
  `SettingsView.java:356-375`, one field at a time) and by the import worker
  threads, which read it throughout the hot loop
  (`VideoImportService.java:232,243-251`). No field is `volatile`, so a worker
  can observe a half-reset config (new `clusteringThreshold`, old `knnK`). Make
  it an immutable record and swap the reference atomically. That single change
  also removes the field-by-field copy in `onReset` and makes the model trivially
  testable. **High payoff, medium effort.**
- [ ] **Database and filesystem I/O on the JavaFX application thread** — four
  places block the UI: the face context-menu handlers resolve availability through
  a DB round-trip on every right-click (`FaceUi.java:392-401`); `filterFolderFor`
  runs a query *plus* a `Files.exists` per card, so an N-card render costs N DB
  calls and N stat calls (`RandomNameView.java:236`, `FaceNameView.java:388`); and
  `DedupeView.runDecision`/`onSkip` perform DB *writes* inline
  (`DedupeView.java:257,267-277`). Move them to background tasks and batch the
  per-card checks into one query. **High payoff, medium effort.**
- [ ] **`TaskRunner`'s single-slot policy cancels unrelated user actions** — one
  slot means any new task cancels the previous one (`TaskRunner.java:32-35`). For
  "the user re-typed, supersede the old load" that is right; for a view that
  multiplexes navigation and writes it silently destroys work. `FaceNameView` has
  no `setBusy` call at all, so a tag or export simply vanishes with no feedback.
  Give `TaskRunner` keyed or multiple slots. **Medium payoff, medium effort.**
- [ ] **Untracked raw threads and a leaked `HttpClient` on language change** —
  `FaceSortApp.buildMainWindowUi` rebuilds all eight views on every language
  switch (`FaceSortApp.java:238-278`) without cancelling the old views' work.
  Four sites spawn `new Thread(...)` that no `TaskRunner` owns and nothing can
  cancel (`ImportView.java:249`, `FeedbackView.java:149`,
  `FaceUi.installPathTooltip:186`, `TagWithNameDialog.java:181`). Each rebuild
  also constructs a new `FeedbackView` → new `FeedbackService` → new `HttpClient`
  (`FaceSortApp.java:248`), and `HttpClient` is not `AutoCloseable` on Java 17, so
  its selector thread and pool accumulate per language change. Hoist
  `FeedbackService` to a `FaceSortApp` field and give the views a `dispose()`.
  The same rebuild is a UX problem: constructing all eight views afresh discards
  scroll positions, the active name, the View filter text and any half-typed
  name, so per-view state should be persisted (or the views kept and the resource
  bundle rebound) rather than the whole scene graph recreated.
  **Medium payoff, medium effort.**
- [ ] **Import progress starves the FX thread** — the pool fires two
  `String.format` messages per file (`ImportWorkerPool.java:95-96,109-110`), each
  triggering a `Platform.runLater` (`ImportView.java:213`). Importing 10,000
  photos enqueues 20,000 runnables on a thread already competing with 16
  detection workers. Throttle the log and drive the progress bar from one
  throttled callback. Also drop the `synchronized (progress)` on a
  caller-owned listener (`ImportWorkerPool.java:114-120`) — it locks a parameter
  the pool does not own. **Medium payoff, small.**

---

## 3. Performance & scalability

- [x] **`ClusteringService` is O(n²·k) where it should be O(n·k)** — `buildAdjacency`
  calls `indexOf(faces, item.id())` for every one of the n·k neighbour hits
  (`ClusteringService.java:164,179-186`), and `indexOf` is a plain linear scan
  (`:174-186`). The whole point of the HNSW index is sub-linear neighbour
  lookup, and this undoes it: ~50k unnamed faces at `knnK=20` is ~5·10¹⁰
  comparisons. A `Map<Long, Integer>` built once (or keying the embedding item by
  position) fixes it. **Highest single performance payoff, tiny effort.**
  *Done — the private `EmbeddingItem` record now carries the face's position in
  the list it was built from (`record EmbeddingItem(Long id, float[] vector,
  int position)`), so `buildAdjacency` reads `result.item().position()` instead
  of scanning for the id: the neighbour pass is now O(n·k) with no map and no
  hashing, and the `indexOf` linear scan is deleted outright. The self-match
  check became a position comparison (`j == i`), which also subsumed the old
  `j >= 0` guard — every index item was inserted from the same list, so the
  unknown-id branch was unreachable. `buildIndex` and `EmbeddingItem` went from
  `private` to package-private purely as a test seam, pinned by
  `ClusteringServiceTest.buildIndex_carriesThePositionOfEachFaceInTheList`,
  which builds an index over faces with deliberately non-monotonic ids
  (70, 10, 40) and asserts the id→position map is exactly
  `{0→70, 1→10, 2→40}` — so a position derived from anything but the list order
  fails. The four existing behavioural `clusterUnnamed_*` tests still cover
  `buildAdjacency` end to end.*
- [x] **Deduplicate candidate building issues O(n²) SQL queries** — one
  `notDupeDao.isNotDupe(...)` per name pair (`DeduplicationService.java:243-254`);
  1,000 names is ~500,000 serialized `SELECT`s behind the one global connection
  monitor. `NotDupeDao.findAll` already exists (`NotDupeDao.java:70`) and has no
  production caller — hoist it into an in-memory `Set` of normalized
  `minId:maxId` keys. This also settles the fate of `NotDupeDao.findAll` in §4:
  adopt it here or delete it there. **High payoff, small.**
  *Done — `buildCandidates` now reads `not_dupes` once via
  `NotDupeDao.findAll()` into a `Set<String>` of `minId:maxId` keys
  (`loadNotDupeKeys`) and filters each pair with `notDupes.contains(key(...))`,
  reusing the class's existing `key(...)` helper so the persisted and the
  skipped-this-run pair keys share one normalization. Pair filtering is now
  O(1) in-memory, and building the ranking costs one database round-trip
  instead of one per pair: 15 pairs went from 15 `SELECT`s to 1. Pinned by
  `DeduplicationServiceTest.nextPair_queriesNotDupesOnceRegardlessOfPairCount`,
  which builds 6 comparable names (15 pairs), hands the service a
  `NotDupeDao` on a counting `Connection` proxy and asserts exactly 1 statement
  touching `not_dupes` — it failed with `expected: <1> but was: <15>` before the
  change, and it pins the query count rather than the chosen data structure.
  A second test,
  `nextPair_offersOnlyThePairsThatAreNotMarkedAsDistinct`, covers 3 identical
  names with 2 of the 3 pairs marked as distinct (one stored in reverse
  order) so the loaded set is proven to be consulted for every pair. The
  `isNotDupe` call site was the last production user of that DAO method, so it
  is now added to the §4 dead-code list.*
- [x] **Every face query hydrates both BLOB columns** — `SELECT_COLUMNS` always
  projects `embedding` and `sub_image_jpg` (`FaceDao.java:33-34`), so even a
  thumbnail-list read pays a 512-float array plus a JPEG per row.
  `findUnnamed()` (`:119-128`) is the hot path for both `findSimilarUnnamed` and
  the clustering load. Add a `SELECT_COLUMNS_NO_BLOBS` variant for the display
  paths; `mapRow` already reads columns by name, so the split is low-risk.
  **High payoff, small.**
  *Done, but **re-scoped after the premise turned out to be wrong** — recording
  the correction here so the item is not re-attempted as originally written. Two
  facts a full call-site audit (every production caller of all six `FaceDao`
  read methods) turned up:*
  1. *The "display paths" mostly **do** need `sub_image_jpg` — that BLOB **is**
  the thumbnail they render (`FaceUi.toImage` and `TagWithNameDialog.toImage`
  are its only readers in the whole UI layer). Only **two** production call
  sites need neither BLOB, and both did the byte-identical
  `findByNameId` → `LinkedHashSet<imageHash>` dance:*
  `ViewService.getImagesForName` (the View tab, once per name) and
  `FaceToNameService.exportImagesForName`.*
  2. *A projection without `embedding` **cannot produce a `FaceRecord` at all**,
  because `FaceRecord` does `requireNonNull(embedding)`
  (`FaceRecord.java:36`). "`mapRow` already reads columns by name, so the split
  is low-risk" is true of the *column* list and false of the *record* — the
  no-BLOB variant would have needed a new model type plus a `FaceUi` overload,
  not just a second column list.*

  *What landed: a single-purpose projection instead, `FaceDao.findImageHashesByNameId`
  — `SELECT image_hash ... GROUP BY image_hash ORDER BY MIN(id)`. It returns the
  distinct hashes, so it hydrates neither BLOB and does not construct a
  `FaceRecord` at all, and the duplicated `LinkedHashSet` de-duplication moved
  into the SQL. `ORDER BY MIN(id)` pins the "order the faces were found" that
  the Javadoc promised, instead of leaving it to the query planner's scan
  choice. Both callers adopted it and each lost a loop; the View tab stops
  reading a 2 KB embedding plus a JPEG for every face of every name, which
  matters because a name can have thousands of faces. Pinned by 4 new tests,
  all written first: `FaceDaoTest.findImageHashesByNameId_returnsDistinctHashesInFirstFaceOrder`,
  `..._emptyForNameWithoutFaces`, `..._doesNotProjectTheBlobColumns` and
  `ViewServiceTest.getImagesForName_doesNotHydratePerFaceBlobs` — the last two
  run the call through the shared `QueryCountingConnection` test utility and
  assert the recorded SQL never mentions `embedding` or `sub_image_jpg`, which
  is the actual payoff stated as a contract. That utility was extracted from
  the §3 dedupe-candidate item's test so both items share one copy.*

  *Still open, and correctly so: `findUnnamed`, `findByNameId` and `findById`
  are **off limits** for a no-BLOB variant — every production caller of all
  three reads `embedding()` (clustering, `findSimilarUnnamed`, all of
  `FaceToNameService`, `DeduplicationService`). Their BLOB cost is the next
  item's problem, not this one's. The one remaining display path that only
  needs the JPEG and not the embedding is
  `NamingService.findRandomUnnamed` → `RandomNameView`; stripping the embedding
  there needs a new lightweight record (e.g. `FaceThumb(id, imageHash,
  subImageJpg)`), a `FaceUi.faceCard` overload and a
  `NamingService`/`RandomNameView` signature change, i.e. **medium effort, not
  small** — left as new work below.*
- [ ] **The random-faces view hydrates embeddings it never uses** —
  `NamingService.findRandomUnnamed` is a pass-through to
  `FaceDao.findRandomUnnamed` (`NamingService.java:219-227`), and its only
  production caller is `RandomNameView`, which reads just `id()`,
  `imageHash()` and `subImageJpg()` — `FaceUi.faceCard` renders the thumbnail
  and nothing computes a similarity. So the Random-faces tab pays a 512-float
  array per card for nothing. Fix: a lightweight `FaceThumb(id, imageHash,
  subImageJpg)` record plus a `findRandomUnnamedThumbs` projection, an
  `FaceUi.faceCard` overload and the two signature changes. This is the
  *remaining* half of the "every face query hydrates both BLOBs" item above,
  which could not be done as one change because `FaceRecord` forbids a null
  embedding. *Found by the call-site audit for that item.*
  **Medium payoff, medium effort.**
- [ ] **`findSimilarUnnamed` loads and sorts the whole unnamed corpus to keep a
  handful** — after *every* tag it materialises every unnamed face with both
  BLOBs, scores all of them, sorts everything, then keeps `limit` (typically 5–10)
  (`NamingService.java:285-308`). It is also the *same algorithm* as
  `NamingService.rankSimilar` (`:311+`) — one sources candidates from the
  database, the other from a list — while `FaceToNameService.findUnnamedForName`
  and `DeduplicationService.buildCandidates` (`:256`) each re-derive the
  score → sort → `subList(0, limit)` truncation independently. Extract one
  `SimilarityRanker` with a shared `topK` helper, and source candidates from the
  long-lived HNSW index rather than the database. **High payoff, medium effort.**
- [x] **Two N+1 reads over the same embeddings** —
  `FaceToNameService.averageOfOtherNames` issues one `faceDao.findByNameId` per
  name (`FaceToNameService.java:193-206`), and `DeduplicationService.loadNamesWithAverages`
  (`:265-281`) has the same shape. One grouped `findByNameIds` (or a `GROUP BY`
  variant of the existing `findByIds`) serves both. **Medium payoff, small.**
  _Resolution: new `FaceDao.findByNameIds(List<Long>)` returns every requested name's
  faces in one grouped query set keyed by name id (empty list for a name without
  faces, so callers never null-check); ids are de-duplicated and chunked through the
  existing `MAX_IN_IDS`/`placeholders` seam, and within a name the faces keep their
  stored order. Both callers now issue one query instead of one per name, and
  `findByIds` reuses the same `placeholders` helper. Pinned by four `FaceDaoTest`
  cases (grouping, duplicate/unknown ids, empty input, chunking) and by two query-count
  tests that compare the face-query count at 1 vs 7/6 names._
- [ ] **Path-prefix filters cannot use an index** — `image_paths` and `video_paths`
  are keyed `(hash, path)` (`Database.java:161-167,222-228`) with no index on
  `path`, yet every prefix filter is `substr(path, 1, length(?)) = ?`
  (`FaceDao.java:392-402`, `DataRemovalDao.java:56,73`). A function on the column
  forces a full scan per query, and the path filter is a hot path (it runs per
  card today — see the FX-thread item in §2). Add an index on `path` and rewrite
  the predicate as a lexicographic range (`path >= ? AND path < ?`).
  **Medium payoff, medium effort.**
- [ ] **SQLite runs without WAL or a busy timeout** — the connection sets only
  `PRAGMA foreign_keys = ON` (`Database.java:38,57`). Without `journal_mode = WAL`
  a reader blocks the writer, which matters because reads and writes interleave
  from several `TaskRunner` threads through the one shared connection; without
  `busy_timeout` a concurrent write fails immediately with `SQLITE_BUSY` instead
  of waiting. Also worth pairing with `synchronous = NORMAL` and a periodic
  `PRAGMA optimize` (mass deletes are a shipped feature, so the file fragments
  badly). **High payoff, small.**
- [ ] **The import folder is walked twice, and the import log grows without bound**
  — `ImportCoordinator` calls `importFolder` per phase (`ImportCoordinator.java:70-76`),
  and each phase independently runs its own `Files.walkFileTree`, so a large
  library is traversed twice for a snapshot that can differ between the two walks.
  A single `collect` that partitions the files would halve the I/O and make both
  phases consistent. Separately, `ImportView.appendLog` appends to the `TextArea`
  indefinitely (`ImportView.java:288`) — cap the lines. **Medium payoff, small.**
- [ ] **The View tab re-decodes every JPEG on every keystroke** — `applyFilter`
  rebuilds all cards and re-reads all thumbnails through `FaceUi.toImage` on the
  FX thread per character typed (`ViewView.java:111,173-191`). Add a debounce and
  a small `Image` cache keyed by hash. **Medium payoff, small.**

---

## 4. Clean code, duplication & dead code

- [ ] **`PATH_FILTER_PLACEHOLDERS` documents a literal instead of binding it** —
  the constant is referenced only from Javadoc (`FaceDao.java:386`) while
  `bindPathFilter` hard-codes `5` in its loop (`:419`). If `pathFilterClause()`
  (`:392-402`) ever gains or loses a `?`, the code silently binds the wrong
  parameters with no compile error. Use the constant. **Tiny.**
- [ ] **`NamingService` carries a dead `config` field** — assigned and
  `requireNonNull`d at `NamingService.java:82` and never read anywhere in
  `src/main` or `src/test`; the Javadoc even says "reserved for tuning"
  (`:66`). Remove the field, the parameter, the import and the argument at
  `FaceSortApp.java:222`. **Tiny.**
- [ ] **Eight DAO methods have no production caller** — `NotDupeDao.isNotDupe`
  (dead since §3's dedupe-candidate item moved to `NotDupeDao.findAll`),
  `NotDupeDao.findByNameId`, `NotDupeDao.deleteForName`, `FaceDao.findAll`,
  `FaceDao.delete`, `VideoDao.findByHash`, and `ImageDao.findByHash` /
  `getAllHashes` (which are the only reason the whole `ImageRecord` type exists).
  All are exercised only by tests. `NotDupeDao.findAll` used to be on this list
  and no longer is — it was adopted by the dedupe-candidate fix in §3. Adopt the
  rest where it makes sense or delete them with their tests. **Small.**
- [ ] **The face-source bridge is rebuilt in all five views, and the failure
  handler in all five** — the 6-line `handleFailure` is pasted into
  `NameFaceView.java:414`, `ViewView.java:398`, `DedupeView.java:327`,
  `RandomNameView.java:362` and `FaceNameView.java:710` (differing only in the log
  key), and five ~25-line anonymous `FaceActions.Source` implementations repeat
  the same four delegations to services that all expose the same four methods.
  `FaceUi.showError` already exists; give it a log-key parameter and make
  `FaceActions` depend on a narrow interface instead of five bridges.
  **Medium effort, mechanical.**
- [ ] **`ViewService` duplicates its own API and its callers duplicate it too** —
  it exposes the same operations as both statics and instance methods
  (`resolveOriginalFile` `:129` / `isOriginalAvailable` `:225`,
  `openInDefaultViewer` `:189` / `openOriginal` `:213`,
  `resolveContainingFolder` `:241` / `openContainingFolder` `:289`), so callers
  must guess which form to use and the statics repeat `requireNonNull` triples.
  Below that, the same "resolve a face to an existing original" logic is
  copy-pasted four ways across `NamingService`, `FaceToNameService`,
  `DeduplicationService` and `ViewService`. Since `ViewService` already holds the
  DAOs, make the statics private helpers and collapse the four copies into one
  injected `MediaFileResolver`. **Medium effort.**
- [ ] **Four stragglers that never made it into `FaceUi`** —
  `TagWithNameDialog.toImage` (`:193`) is a character-for-character copy of
  `FaceUi.toImage` (`:100`); `SettingsView.tooltip` (`:213`) copies
  `FaceUi.wrappedTooltip` (`:150`); `RandomNameView.java:111` builds a *third*
  bare `new Tooltip(...)` with no `setWrapText(true)`, so that one hint renders as
  a single unreadable long line — exactly the inconsistency the shared helper was
  introduced to remove; and `filterFolderFor`'s `Files.exists` + prefix logic is
  pasted into both `RandomNameView.java:236` and `FaceNameView.java:388`. Route
  all four through the existing helpers. **Small, mechanical.**
- [ ] **Small dead code and drift traps** — the `.grid-of-faces` and
  `.candidate-check` CSS selectors (`styles.css:142,189`) are referenced by no
  Java code; `ImportCoordinator.java:15-26` contains the same Javadoc block
  twice, the first copy orphaned; `ClusteringService.java:110-112` null-checks
  `face.embedding()`, which `FaceRecord` guarantees is non-null, so the dead
  half of the condition misleads readers; `ImportView.java:276` hard-codes
  `AppConfig.DEFAULT_CONFIG_FILE` although `SettingsView` has that path injected;
  `FrameSampler.java:41` uses a bare `1000` where `MIN_FRAME_MS_SPACING` is
  defined 24 lines above; `ConfigModel.java:52-56,70+` mixes seven bare default
  literals (`80`, `0.8`, `10`, `0.5`, `16`, `200`, `100`, `20`) in with four
  properly named `DEFAULT_*` constants in the same block; and constructor
  contracts are enforced inconsistently — `DeduplicationService` applies
  `requireNonNull` to all eight parameters (`:66-73`) but `ClusteringService`
  applies none (`:62-66`), while `FeedbackService` documents "must not be null" and
  "must be positive" in its Javadoc (`:54-69`) without enforcing either. Pick one
  convention and apply it. **Small, mechanical.**

---

## 5. Tests, TDD & process

- [ ] **CI never runs the test suite** — both jobs build with `-DskipTests` and no
  test job exists (`.github/workflows/build.yml:33,81`). All 357 tests are
  therefore only ever run locally, and a red suite can still produce a release.
  Add a `test` job running `mvn -B test` on `pull_request`, and make `release`
  depend on it. **Highest-leverage process gap in the repo, tiny effort.**
- [ ] **No static analysis or coverage gate** — the pom has no JaCoCo, SpotBugs,
  PMD, Checkstyle or Enforcer plugin, so nothing catches dead code, accidental
  null-dereference patterns or complexity regressions. At minimum add JaCoCo with
  a floor and `maven-enforcer-plugin` pinning the Java 17 toolchain. **Medium effort.**
- [ ] **12 of the 15 classes in `ui/` have no tests, and the three that do test
  extracted helpers rather than views** — only `FaceNameViewTest`, `ViewViewTest`
  and `ModelDownloadViewTest` exist, and they exercise the pure static seams
  (`ViewView.filterNames`, `FaceNameView.rangeSelection`) that were extracted
  precisely to be testable. Keep extending that pattern into
  `SettingsView.onReset` (the field-by-field default copy), `DedupeView`'s
  decision state machine and `NameFaceView`/`RandomNameView` selection logic —
  all of which are currently unreachable from a headless test. **Medium effort.**
- [ ] **Views hard-wire concrete services, so they cannot be tested** — each view
  takes concrete service types in its constructor, so a test must stand up a real
  DB-backed service graph. The codebase already has the right template in
  `UpdateChecker`'s package-private `RemoteFetcher` seam; a narrow `TaggingService`
  / `ReviewService` interface per view would give the UI the same testability
  without changing behaviour. **High effort payoff, medium effort.**
- [ ] **No regression test exists for most of the §1 bugs** — items 1.2 (poisoned
  video), 1.3/1.4 (config write/load), 1.5 (re-export), 1.6
  (over-broad catch) and 1.7 (null estimate) are all reachable from tests today.
  Write the failing test first when fixing each, per `AGENTS.md`'s TDD rule, so
  every fix is pinned rather than merely applied. **Bundle with §1.**
  *1.1 is done — see the note on the item itself.*

---

## 6. Build, CI & dependencies

- [ ] **The pom repeats itself five times** — the five platform profiles are ~528
  lines, of which ~190 are `<exclude>` entries; a Python set comparison shows
  17–45 of them are identical between any two profiles (45 shared between the two
  Linux ones). The sqlite-jdbc, JNA, pytorch-jni, ffmpeg and javacpp native
  exclusions are copy-pasted per platform with only the kept natives differing.
  Drive the filters from a single property per profile (or one
  `${platform.suffix}`-parameterised filter block) and collapse ~600 lines to a
  fraction. Verify by byte-comparing the produced jars' contents per platform.
  **Medium effort, mechanical, low risk.**
- [ ] **Dependencies are cloned from a moving branch at build time** — CI does
  `git clone --depth 1 .../FaceAI.git` (default branch, **unpinned**) and
  `mvn install -DskipTests -f faceai-deps/pom.xml` on every job
  (`.github/workflows/build.yml:25-32,73-86`), so an upstream change silently
  breaks or alters every release. ffmpeg4j *is* pinned to a commit; FaceAI should
  be too, or vendored as a submodule with a recorded version. **Medium payoff, small.**
- [ ] **Actions are pinned to major tags, and releases carry no version** — the
  workflow uses `actions/checkout@v5`, `actions/cache@v4`,
  `actions/upload-artifact@v5` etc. by floating tag, so a compromised upstream tag
  runs with the repo's token. Pin to commit SHAs. Relatedly, releases are tagged
  `build-<run_number>`, which leaves users with no semver to reason about — worth
  deciding on a version scheme before 1.0. **Medium payoff, small.**

---

## 7. UX & accessibility

- [ ] **No keyboard shortcuts, mnemonics or accessible text anywhere in `ui/`** —
  greps for accelerators, `setMnemonicParsing`, `Mnemonic`, `Shortcut` and
  `setAccessibleText` return nothing across the whole package. Every button is
  mouse-only, and a screen reader announces face thumbnails with no meaningful
  name. Start with `setAccessibleText` on each face thumbnail and mnemonics on
  the dialog buttons (`RemoveByPrefixDialog`, `TagWithNameDialog`). Tooltips are
  equally inconsistent: `SettingsView` attaches an explanation to all 20 controls,
  but the action buttons in `DedupeView`, `ViewView` and `ImportView` have none,
  so *Skip* vs. *These are dupes*, *Tag with a different name* and *Reveal in
  folder* are undiscoverable. **Medium payoff, medium effort.**
- [ ] **Destructive actions lack confirmation, and the safe pattern already
  exists** — `ViewView.untagFaces` (`:359`) removes name tags with no confirmation
  and no undo, and `DedupeView`'s merge is irreversible and unconfirmed, yet
  `RemoveByPrefixDialog` implements exactly the right two-step Preview → Remove
  flow. Apply the same pattern to both, and add progress/cancel to the removal
  itself (`DataRemovalService` deletes tens of thousands of rows behind a bare
  busy indicator). **High payoff, small.**
- [ ] **No sorting in any list** — neither `ViewView` nor `FaceNameView` can sort
  by name, face count or recency. `NameDao.SELECT_WITH_FACE_COUNT` already
  computes the count, so sorting by it is nearly free, and for a list of hundreds
  of people alphabetical ordering is table stakes. Pairs naturally with the
  existing View search filter. **Medium payoff, small.**

---

## 8. i18n & documentation

- [ ] **The "Video import" feature bullet is missing from all five translated
  READMEs** — `README.md:38-41` has a dedicated *Video import* bullet; none of
  `README.de.md`, `README.es.md`, `README.fr.md`, `README.ru.md` or
  `README.zh.md` has it (each has 14 feature bullets where the English original has
  15). `README.fr.md` is worse: it contains **no mention of video at all**, so its
  format list, the `importFolder` description and the download section are all
  missing the feature. *(This is the previously open "translated READMEs lack
  video support" item, now precisely scoped — the old wording claimed zero
  mentions in all five, which was only true of the French one.)*
  **Low effort, required by the `AGENTS.md` doc-sync rule.**
- [ ] **An i18n key is translated six times and never used** —
  `ui.faceName.pathFilterPrompt` is defined in all six bundles
  (`messages*.properties:124`) but `FaceNameView` never sets a prompt text for
  its path-filter field (`RandomNameView` uses `ui.randomName.pathFilterPrompt`
  instead). Either wire it up — it would help users discover the filter — or
  remove it from all six files. **Tiny.**
- [ ] **Similarity scores are rendered two different ways, and two keys
  duplicate each other** — `ui.dedupe.round` formats with a bare `%.2f`
  (`DedupeView.java:205`, `messages.properties:183`) while every other similarity
  display goes through `I18n.percent` (locale-correct, whole-percent). Unify on
  `I18n.percent`. Separately, `ui.view.untagFailed` ("Could not untag faces.")
  and `ui.view.untagFailedHeader` ("Could not untag faces") say the same thing
  (`ViewView.java:375-376`) and will drift apart across the five translations —
  collapse to one key. **Small.**
- [ ] **`I18n` is global mutable state behind a lock on every lookup** — the
  active locale lives in a static field mutated at runtime by the Settings tab,
  and every lookup is `static synchronized` (`I18n.java`), so a global monitor is
  contended on every string in the app. A `ResourceBundle`-scoped instance handed
  to each view would remove both the shared-mutable-state hazard and the lock;
  the static façade can stay as a convenience. **Medium payoff, medium effort.**

---

## 9. Feature ideas

- [ ] **GPU inference option** — expose `FaceAiService.DEVICE`, currently
  hard-coded to `"CPU"` (`FaceAiService.java:28,170`), in Settings. Still
  **blocked on the `faceai` library**: its DJL `Criteria` builders ignore
  `device()`, so exposing the option today would be a visible no-op. Push
  device support upstream first, then add the config field wired through
  `toFaceAIConfig`. Would speed up clustering and import on CUDA machines.
  **Low–medium effort once unblocked.**
- [ ] **Re-detect faces when the detection settings change** — detection
  parameters are per-image, stored on the `images` row as `criteria_json`, but
  changing `minConfidence` / `maxFacesPerImage` / `minBoundingBoxSize` in Settings
  has no effect on already-imported media, with nothing telling the user so. A
  "re-detect N images" action (reusing the existing `ImportService` pipeline and
  its dropped-face accounting) would close that loop. **Medium effort.**
- [ ] **Undo for the last tagging/dedupe action** — untagging and merging are the
  two irreversible operations in the app; a single-level undo buffer over the
  affected face ids would be cheap given both already run in a transaction.
  **Medium effort, high user-visible value.**
