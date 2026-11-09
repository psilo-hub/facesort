# Most similar tab — performance & UX to-do list

How to use this file: items are numbered and meant to be worked through as a
to-do list. Mark an item done by changing `[ ]` to `[x]` and striking its
title through (`~~like this~~`). Each item lists the items it **depends on**
(do those first, or at least be aware of them) and a detailed description of
what to do.

## Problem

The "Most similar" tab offers the single (name, unnamed face) pair with the
highest similarity in the whole library, one pair at a time.

`MostSimilarService.findBestSuggestion()` is the hot path. For **every name** it:

1. calls `FaceToNameService.findUnnamedForName(nameId, 25)`, which
   - loads the name's tagged faces (`faceDao.findByNameId`) and computes their
     average embedding,
   - loads **all** unnamed faces (`faceDao.findUnnamed`), including their
     `sub_image_jpg` thumbnail blobs, because the query uses `SELECT_COLUMNS`,
   - scores every unnamed face against the average (`calcSimilarity` on
     512-dim vectors), sorts the results and keeps the top 25;
2. calls `FaceToNameService.findMostSimilarNamed(nameId, 1)`, which loads the
   name's tagged faces **again**, recomputes the same average **again** and
   scores every tagged face against it.

That is **O(names × unnamed)** work, plus repeated blob loading and repeated
average computation, and it runs on **every decision** (same person / not the
same person / skip / not a face). With a large library the tab feels sluggish
and the UI appears frozen between decisions.

## Progress overview

| #  | Item                                              | Depends on | Status |
|----|---------------------------------------------------|------------|--------|
| 1  | Benchmark test for `findBestSuggestion()`         | —          | [x]    |
| 2  | Slim projection: stop loading thumbnail blobs     | 1 (measure)| [x]    |
| 3  | Cache name average embeddings                     | 1          | [x]    |
| 4  | Single-pass scoring over one unnamed-face load    | 2, 3       | [x]    |
| 5  | Prune names and skip the redundant second query   | 3, 4       | [x]    |
| 6  | Configurable / adaptive early-exit threshold      | 5          | [ ]    |
| 7  | Partial index for unnamed faces                   | 1          | [x]    |
| 8  | Parallel scoring across names                     | 3, 4, 5    | [x] ~~Parallel scoring across names~~ |
| 9  | Keep the unnamed pool in memory between decisions | 2, 4       | [ ]    |
| 10 | In-memory nearest-neighbour index (HNSW)          | 4, 9       | [ ]    |
| 11 | Prefetch the next suggestion                      | 3, 4       | [x]    |
| 12 | Scan progress indicator and cancel                | 4          | [ ]    |
| 13 | Backlog counter                                   | 4 (or 7)   | [ ]    |
| 14 | Batch review of the top-K pairs                   | 3, 4, 5    | [ ]    |
| 15 | Persist name averages across restarts (optional)  | 3          | [ ]    |

Suggested order: 1 → 2 → 3 → 4 → 5 → 7 → 6 → 11 → 12 → 13 → 8 → 9 → 14 → 10 → 15.
Items 2 and 5 are the cheapest and most likely to give the largest speedup.

---

## Measurement

### 1. [x] ~~Benchmark test for `findBestSuggestion()`~~

**Depends on:** nothing. Do this first so every later item can be validated.

**What to do:**
- Add a test (e.g. `MostSimilarServiceBenchmarkTest`) that builds a synthetic
  library in an in-memory/temporary SQLite database: at least 100 names with a
  handful of tagged faces each, and at least 1000 unnamed faces, using the
  existing `FakeFaceAiEngine` or random 512-dim vectors.
- Time a full `findBestSuggestion()` call and a short sequence of decisions
  (e.g. 5 × markDifferentPerson/skip followed by a new suggestion), since the
  per-decision cost is what the user feels.
- Assert a generous upper bound so the test is not flaky on slow machines, and
  print the measured time. Record the baseline numbers in the commit message
  or in this file so later items can show before/after figures.
- Add a correctness test that fixes the expected best pair for a small,
  hand-built library. Every optimisation below must keep this test green.
- Keep the benchmark out of the normal fast test run if it is slow (e.g. a
  JUnit tag), but make sure it can be run with `mvn test`.

**Done (2026-11-08):** `MostSimilarServiceBenchmarkTest` seeds 100 names with
four tagged faces each and 1000 unnamed 512-dimensional faces in an in-memory
database, prints the measured time and checks a 30-second budget. It also pins
a small hand-built best pair. The whole test takes well under a second headless,
so it runs in the normal `mvn test` suite. Baseline after items 2–5 and 11:
cold scan ≈ 135 ms; five decisions ≈ 332 ms (≈ 66 ms per decision); total
≈ 467 ms. Benchmark runs will print the current figures for later comparisons.

---

## Algorithmic / service optimizations

### 2. [x] ~~Slim projection: stop loading thumbnail blobs while scoring~~

**Depends on:** 1 (to measure the gain). Independent of the others otherwise.

**What to do:**
- `FaceDao.findUnnamed()` and `findByNameId()` select `SELECT_COLUMNS`, which
  includes the `sub_image_jpg` blob. Scoring only needs `id` and `embedding`
  (plus `image_hash`/`name_id`), so the blobs are read from SQLite for nothing,
  once per name per decision.
- `FaceDao` already has `SELECT_SLIM_COLUMNS` and `mapSlimRow` (used by a
  grouped lookup by name ids). Add slim variants such as
  `findUnnamedSlim(pathPrefix)` and `findByNameIdSlim(nameId)` (or one grouped
  `findByNameIdsSlim`), keeping the `NOT_A_FACE_CLAUSE` filter on the unnamed
  query.
- Use the slim variants in the scoring paths of `FaceToNameService`
  (`findUnnamedForName`, `findMostSimilarNamed`) when the caller does not need
  the crop. Add an overload or an internal method rather than changing what the
  existing public methods return, because other tabs show the thumbnails.
- In `MostSimilarService`, once the best pair is known, load the full records
  (with `sub_image_jpg`) for just the two faces via `findByIds`, so the
  `Suggestion` record still carries what `MostSimilarView` needs to render the
  cards.
- Test: slim query returns the same ids/embeddings as the full query; the
  `Suggestion` returned by the service still has non-null crops.

**Done (2026-11-08):** `FaceDao.findUnnamedSlim`, `findByNameIdSlim` and
`findUnnamedByIds` split scoring from hydration. `FaceToNameService` ranks on
slim rows and hydrates only the returned faces, and `MostSimilarService` uses
the non-hydrating `findUnnamedForNameSlim` / `findMostSimilarNamedSlim` during
the scan, then loads just the two shown faces for the `Suggestion`.

### 3. [x] ~~Cache name average embeddings~~

**Depends on:** 1 (to measure).

**What to do:**
- Introduce a small cache (e.g. `NameAverageCache`, an immutable-value
  `Map<Long, float[]>` behind a class) owned by the service layer. Compute each
  name's average once and reuse it in both `findUnnamedForName` and
  `findMostSimilarNamed`, which currently each recompute it.
- Within a single `findBestSuggestion()` call this removes the duplicate
  computation; across decisions it removes all of it except for the name whose
  tagged set changed.
- Invalidate precisely: drop one name's entry when a face is tagged to it or
  untagged from it, when the name is deleted or merged, and when a tagged
  face's embedding changes (e.g. `FaceDao.updateSubImage` from the rotation
  migration). Drop everything on bulk operations (import, data removal,
  deduplication) if per-name invalidation is not practical.
- Since `FaceToNameService.tagFace/tagFaces` and other tabs also change tagging,
  the invalidation must live where all writes pass through (service layer), not
  only in `MostSimilarService`. If that is not feasible, key the cache entry by
  `(nameId, tagged face count, max tagged face id)` as a cheap staleness check.
- Test: cached and uncached averages are identical; tagging a face through any
  path changes the next suggestion's similarity accordingly.

**Done (2026-11-08):** `NameAverageCache` caches each name's average in
`FaceToNameService`. `FaceDao.writeVersion()` is bumped by every face write, and
the cache drops its entries when it sees a newer version, so tagging in any
tab, untagging, a merge and the rotation migration all invalidate it without
each service knowing about the cache.

### 4. [x] ~~Single-pass scoring over one unnamed-face load~~

**Depends on:** 2 (slim rows), 3 (cached averages).

**What to do:**
- Restructure `findBestSuggestion()` so it loads the unnamed pool **once** per
  scan (slim rows, minus not-a-face faces) and all name averages once, then
  scores in a single pass, instead of N per-name queries that each scan the
  whole unnamed table.
- Implementation sketch: for each name, take its average and its rejected face
  ids (`notMatchDao.findFaceIdsByNameId`, or one bulk query for all names to
  avoid N round trips), iterate the shared in-memory unnamed list, skip
  rejected/skipped/below-`minNameSimilarity` candidates and track only the best
  candidate per name (no per-name sort, no top-25 list; the skip handling needs
  just "best not skipped").
- Keep behaviour identical: the globally highest `nameSimilarity` wins,
  `minNameSimilarity` is honoured, `not_matches` and session skips are
  respected.
- Consider a bulk `NotMatchDao` query returning all rejections as
  `Map<Long, Set<Long>>`.
- Test: result equals the old implementation on the correctness fixture from
  item 1 and on randomised libraries (compare old vs new in a test while the
  old code still exists).

**Done (2026-11-08):** `MostSimilarService.findBestSuggestion` loads the slim
unnamed pool, all name averages and all rejections once, then scores every name
against the shared rows and keeps only each name's best non-skipped candidate.
`NotMatchDao.findFaceIdsByNameIds` provides the single chunked rejection lookup.
The scan no longer has a per-name top-25 list, so skipping still works after
more than 25 candidates were skipped.

### 5. [x] ~~Prune names and skip the redundant second query~~

**Depends on:** 3, 4.

**What to do:**
- Today `findMostSimilarNamed` (tagged-face representative lookup) runs for
  every name that has any candidate, even when that name cannot win. Compute the
  name's best candidate similarity first and only look up the representative
  tagged face when it beats the current global best `nameSimilarity`.
- Better still, defer the representative lookup until the very end: track the
  best (name, candidate) across names, then compute the representative tagged
  face and `faceSimilarity` once for the winner only.
- With cached averages (item 3) the representative lookup becomes a scan of
  one name's tagged faces against its cached average.
- Test: the pair and both similarity values are unchanged; add a test with a
  counting `FaceAiService` wrapper to assert the number of `calcSimilarity`
  calls dropped.

**Done (2026-11-08):** `findBestSuggestion` keeps only the best
(name, candidate) pair while scanning, then loads the winner's tagged
representative and computes `faceSimilarity` once at the end. The representative
scoring therefore no longer runs for every name that has a candidate.

### 6. [ ] Configurable / adaptive early-exit threshold

**Depends on:** 5 (the scan loop in its final shape).

**What to do:**
- `findBestSuggestion()` returns immediately when `nameSimilarity >= 0.99`.
  Move that constant into a named constant, then consider making it
  configurable in `ConfigModel` (with a settings entry and tooltip, as the
  project does for other settings) or adaptive (e.g. relative to
  `minNameSimilarity` or to the distribution seen so far).
- If a config key is added, follow the repository rule: no config-file backward
  compatibility break, update `README.md` and all five translated READMEs,
  `CHANGELOG.md` and `todo.txt`.
- Note that the early exit makes the result "first pair above threshold" rather
  than "global best" when a later name would score even higher; document this
  trade-off in the Javadoc.
- Test: threshold behaviour at, just below and just above the value.

### 7. [x] ~~Partial index for unnamed faces~~

**Depends on:** 1 (to measure whether it matters once items 2–4 are in).

**What to do:**
- `idx_faces_name_id` exists, but queries of the form `name_id IS NULL AND NOT
  EXISTS (SELECT 1 FROM not_faces ...)` may still scan. Check with
  `EXPLAIN QUERY PLAN` what SQLite does today.
- If useful, add `CREATE INDEX IF NOT EXISTS idx_faces_unnamed ON faces(id)
  WHERE name_id IS NULL` in `Database` next to the other index statements, and
  confirm the plan uses it. Verify that `not_faces` has an index on `face_id`
  (the `NOT EXISTS` runs per row).
- Existing databases pick the index up automatically through
  `IF NOT EXISTS`; note it in `CHANGELOG.md` only if the speedup is visible.
- Test: `DatabaseTest` asserts the index exists; optionally assert the query
  plan mentions it.

**Done (2026-11-08):** Checked with `EXPLAIN QUERY PLAN` on a synthetic
100 000-face library (90 000 named, 10 000 unnamed, 1 000 dismissals):
`idx_faces_name_id` already serves `name_id IS NULL` with
`SEARCH faces USING INDEX idx_faces_name_id (name_id=?)`, and the dismissal
check is an integer-primary-key search on `not_faces`. The proposed
`idx_faces_unnamed ON faces(id) WHERE name_id IS NULL` was not chosen by the
planner, and forcing it gave no measurable gain (the id-only projection took
~5 ms with the name index and ~3 ms with the partial index, inside noise),
because `id` is the rowid and the partial index only duplicates the unnamed
rowid subset. No index was added. `FaceDaoTest` now pins the unnamed-pool plan
so an accidental full scan fails the suite.

### 8. [x] ~~Parallel scoring across names~~

**Depends on:** 3, 4, 5.

**What to do:**
- Scoring a name against the shared unnamed list is independent of other names,
  so run it in parallel (parallel stream over names, or an executor sized to
  available cores) and reduce to the best result.
- All database reads (unnamed pool, averages, rejections) must happen before
  the parallel section: the JDBC `Connection` shared by the DAOs is not safe to
  use from several threads at once.
- Replace the early exit with a shared "best so far" (e.g. `AtomicReference`
  or a reduction) and make sure ties resolve deterministically (e.g. lower name
  id wins) so results are reproducible and testable.
- Ensure `FaceAiService.calcSimilarity` is thread-safe for the real engine, or
  switch the hot loop to a plain dot-product over normalised vectors.
- Test: parallel and sequential versions give identical results across many
  random libraries; benchmark from item 1 shows the gain.

**Done (2026-11-08):** `findBestSuggestion()` and `findBestSuggestions(limit)`
now score every name against the shared unnamed pool in parallel via
`Stream.parallel()`, then reduce to the best pair. All DB reads happen in
`loadScan()` before the parallel section, so no JDBC state is touched from
multiple threads. Ties resolve deterministically: the lower name id wins, and
`RankedName.order` preserves scan order for stable tie-breaking in the batch
path. Determinism tests (20 repeated runs plus an exact-tie test) pass.

### 9. [ ] Keep the unnamed pool in memory between decisions

**Depends on:** 2, 4.

**What to do:**
- Instead of reloading the unnamed pool on every decision, hold it in a small
  component (e.g. `UnnamedFacePool`) populated lazily on the first scan.
- Update it incrementally: remove a face when it is tagged (same person) or
  marked not-a-face; leave it in place for "not the same person" (the rejection
  is per name, tracked in the rejection map) and for skips.
- Invalidate/reload when the pool changes through other paths: new imports,
  deduplication, data removal, rotation migration, tagging in other tabs. The
  simplest robust approach is a cheap version stamp (e.g. `COUNT(*)` plus
  `MAX(id)` of unnamed faces) checked at the start of every scan, with a full
  reload when it changed.
- Respect memory: 512 floats × 4 bytes ≈ 2 KB per face, so 100 000 unnamed
  faces ≈ 200 MB; decide whether a size cap or a fallback to per-scan loading is
  needed (see item 10 for a more compact index).
- Test: pool stays consistent after a sequence of tag/reject/skip/not-a-face
  decisions and after an external change.

### 10. [ ] In-memory nearest-neighbour index (HNSW)

**Depends on:** 4, 9.

**What to do:**
- The project already uses HNSW (`ClusteringService`, `SimilarityRanker`). Build
  one index over the unnamed embeddings and query it with each name's average
  embedding to get its top candidates, instead of comparing against every
  unnamed face for every name. This changes the cost from O(names × unnamed) to
  roughly O(names × log unnamed).
- Because HNSW is approximate, over-fetch (e.g. top 25–50 per name) and
  re-score exactly with `calcSimilarity`, and filter rejected, skipped and
  below-`minNameSimilarity` candidates afterwards. Keep the exact scan as a
  fallback and for small pools where building an index costs more than it saves.
- Maintain the index incrementally with item 9 (HNSW libraries often cannot
  remove items; use `remove` if the library supports it or mark ids as dead and
  rebuild when many are dead).
- Reuse `hnswM`, `hnswEfConstruction` and `hnswEfSearch` from `ConfigModel`.
- This is the only item that can change results (recall below 100 %). Validate
  against the exact scan on realistic data and document the observed recall
  before enabling it by default.

---

## UX improvements

### 11. [x] ~~Prefetch the next suggestion~~

**Depends on:** 3, 4 (so the background computation is cheap).

**What to do:**
- While the user looks at the current pair, compute the next best pair in the
  background so "Same person", "Not the same person" and "Skip" feel instant.
- Compute the top few pairs (e.g. best three, distinct faces) once, keep them in
  a small queue, and pop the next valid one on a decision. After every decision
  re-validate queued entries (face tagged elsewhere, pair rejected/skipped) and
  recompute in the background when fewer than N remain.
- Decisions that change a name's average (same person) can reorder the queue;
  either invalidate entries for that name or recompute the queue.
- UI: `MostSimilarView.loadNextSuggestion()` should show the prefetched pair
  immediately and only fall back to the loading placeholder when the queue is
  empty. Keep all queries on background `Task`s via `TaskRunner`.
- Test: service-level test for the queue (validation after tag/reject/skip) and
  a UI-free test that the next suggestion is available without a new scan.

**Done (2026-11-08):** `MostSimilarService.findBestSuggestions(limit)` returns
the best pairs with distinct unnamed faces in descending order.
`MostSimilarQueue` keeps three pairs ready, validates each before showing it,
and lets the view drop exactly the entries a decision invalidated. The view
shows a queued pair without a scan, refills the queue on a background `Task`,
and cancels the prefetch when a decision starts. `TaskRunner.cancel(name)` was
added for that. Queue tests cover tag/reject/skip invalidation and assert that
serving a queued pair issues no new unnamed-pool query.

### 12. [ ] Scan progress indicator and cancel

**Depends on:** 4 (a scan with well-defined steps to report).

**What to do:**
- `loadNextSuggestion()` currently clears the pair and shows a static
  `ui.mostSimilar.loading` label. Report progress from the scan (e.g.
  "scanning 120 / 480 names") by extending the `Task` with `updateMessage` /
  `updateProgress`, or by passing a progress callback into the service.
- Offer a cancel button, or cancel the running scan automatically when a new
  one is requested (e.g. the tab is refreshed again), so scans do not pile up.
  The scan loop must check `Thread.interrupted()`/`isCancelled()` between names.
- Add the new strings to every `I18n` resource bundle.
- Test: a cancelled scan returns promptly and leaves no stale result on screen.

### 13. [ ] Backlog counter

**Depends on:** 4 (the pool size is then known for free) or 7 (a cheap
`COUNT(*)` of unnamed faces).

**What to do:**
- Show how much work remains, e.g. "X unnamed faces left to decide for N
  names", under the pair or in the status label, so the user can judge progress.
- Provide a cheap count method (the number of unnamed, not-a-face-filtered
  faces and the number of names with at least one tagged face); do not run a
  full scan just to count. Optionally show "pairs above the similarity
  threshold" when the scan already computed it.
- Add the strings to every `I18n` resource bundle.
- Test: counts match the database after tagging/rejecting.

### 14. [ ] Batch review of the top-K pairs

**Depends on:** 3, 4, 5 (cheap scoring so a top-K list is affordable).

**What to do:**
- Add a mode that shows the top K (e.g. 12–20) best pairs in a scrollable grid
  with checkboxes and one "Tag selected as same person" button, plus
  "Reject selected" and "Skip all". One batch decision replaces K full rescans
  with roughly one.
- Service: add `findBestSuggestions(int k)` returning distinct unnamed faces
  (a face should appear only once, paired with its best name) in descending
  similarity order, honouring the same rules as `findBestSuggestion()`.
  `findBestSuggestion()` can then become `findBestSuggestions(1)`.
- Apply batch decisions in one database transaction (`tagFaces` already exists
  for bulk tagging) and invalidate the cache entries of all affected names once.
- Keep the existing single-pair flow as the default or as a toggle; reuse
  `FaceUi.faceCard` for the cards.
- Add strings to every `I18n` resource bundle; update `README.md` and all five
  translated READMEs, `CHANGELOG.md` and `todo.txt` when done.

---

## Data / schema

### 15. [ ] Persist name average embeddings across restarts (optional)

**Depends on:** 3.

**What to do:**
- Store each name's average embedding in the database (e.g. a `name_averages`
  table with `name_id`, `embedding BLOB`, `face_count`, `updated_at`) so the
  first scan after startup does not have to rebuild all averages.
- Evaluate cost/benefit first: with items 3 and 4 in place the rebuild is one
  pass over the tagged faces (slim rows), which may already be fast enough.
  Only implement this if the benchmark shows startup cost matters.
- If implemented: keep it in sync with item 3's invalidation (a stored average
  is stale when `face_count` or the tagged set changes), add a migration in
  `Database` without breaking existing databases, and rebuild lazily when a row
  is missing or stale.

---

## Notes

- Do not change the public behaviour of `findBestSuggestion()`: it must still
  return the single globally most similar (name, unnamed face) pair, honour
  `minNameSimilarity`, rejected combinations (`not_matches`), and session-only
  skips. Item 6 (threshold) and item 10 (approximate search) are the only items
  that may knowingly change which pair is returned; both must be documented.
- Any optimisation must remain correct when decisions change the pool
  (tagging removes a face from the unnamed pool; rejecting persists a
  `not_match`; marking a face "not a face" removes it from every naming tab).
- Follow `AGENTS.md` for each item: write a failing test first (TDD), keep
  `mvn test` green, keep commits small, and update `todo.txt`, `CHANGELOG.md`,
  `README.md` and the five translated READMEs when a change is user-visible.
