# Most similar tab — performance & UX suggestions

## Problem

The "Most similar" tab offers the single (name, unnamed face) pair with the
highest similarity in the whole library, one pair at a time.

`MostSimilarService.findBestSuggestion()` (MostSimilarService.java:74) is the
hot path. For **every name** it:

1. loads **all** unnamed faces via `faceDao.findUnnamed()` (full table scan;
   there is no index on `faces(name_id IS NULL)`),
2. scores each unnamed face against that name's average embedding
   (`FaceAiService.calcSimilarity` on 512-dim vectors),
3. recomputes the name's average embedding (once in
   `findUnnamedForName`, again in `findMostSimilarNamed`),
4. scores the name's tagged faces to pick the representative.

That is an **O(names × unnamed)** scan with repeated average recomputation, and
it runs on **every decision** (same person / not the same person / skip / not a
face). With a large library (many names × many unnamed faces) the tab feels
sluggish and the UI appears to freeze between decisions.

## Suggestions (todo list — cross off when done)

### UX improvements (immediate, low-risk)

- [ ] Show a "computing best match…" indicator (already `setBusy(true)`, but add
      a remaining-pair count, e.g. "scanning N names / M unnamed faces") so the
      tab does not appear frozen while scanning.
- [ ] Show progress through the backlog (e.g. "X unnamed faces left to decide
      for N names") so the user knows how much work remains.
- [ ] Batch review: present the top-K candidate pairs in a scrollable list for
      multi-select "same person" tagging instead of one-at-a-time round trips.
      One batch decision replaces N full rescans with ~1.

### Algorithmic / service optimizations (the big wins)

- [ ] Cache name average embeddings: compute each name's average once per
      `findBestSuggestion()` call and reuse it in both
      `findUnnamedForName` and `findMostSimilarNamed`; ideally keep averages
      cached across decisions until a name's tagged set changes (tagging one
      face only invalidates that one name's average).
- [ ] Single-pass scoring: load all unnamed faces once and all name averages
      once, then score in one pass instead of N per-name DB queries each of
      which scans the whole unnamed table.
- [ ] Prune names: skip the tagged-face representative lookup
      (`findMostSimilarNamed`) for any name whose best unnamed candidate is
      already below the current global best `nameSimilarity`.
- [ ] Keep the existing early-exit at ~0.99 similarity
      (MostSimilarService.java:91); consider making the threshold adaptive or
      configurable.
- [ ] Add an in-memory index over unnamed embeddings (e.g. sorted structure or
      approximate nearest neighbours) so each name's top candidates are found
      without scanning every unnamed face.

### Data / schema

- [ ] Add a partial index on `faces(name_id IS NULL)` (unnamed faces) so
      `findUnnamed()` avoids a full table scan; verify SQLite uses it with
      `EXPLAIN QUERY PLAN`.
- [ ] Optionally persist name average embeddings to avoid recomputation across
      restarts (larger change; evaluate cost/benefit).

### Measurement (validate each change)

- [ ] Add a benchmark/test that times `findBestSuggestion()` with a realistic
      library (e.g. 100+ names × 1000+ unnamed faces) and assert it stays under
      a threshold; use it to validate each optimization before merging.

## Notes

- Do not change the public behaviour of `findBestSuggestion()`: it must still
  return the single globally most similar (name, unnamed face) pair, honour
  `minNameSimilarity`, rejected combinations (`not_matches`), and session-only
  skips.
- Any optimisation must remain correct when decisions change the pool
  (tagging removes a face from the unnamed pool; rejecting persists a
  `not_match`).
