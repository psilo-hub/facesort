# FaceSort — Todo (codebase-review follow-ups)

Checklist of the improvement suggestions that are still **open**. The implemented
ones (suggestions 1, 2, 3a, 3b, 4, 5, 6, 7, 8, 9) have been removed — their
resolutions are recorded in `todo.txt` items 7–16 and `CHANGELOG.md`. HNSW index
closing was dismissed with evidence (no `close()` on the pure-Java `HnswIndex`; the
index is a GC-eligible method-local) and the schema-migration and fail-surfacing
feature ideas were absorbed by the implemented work, so they are gone too.

All line numbers below refer to the current state of the codebase
(2026-09-23, full suite green: 306 tests / 35 classes, 0 failures).

---

## 3c. Service-level duplication

- [ ] **Extract a `NameService`** — `createOrFindName` / `findName` are near-identical
  in `NamingService.java:239-249,261-263` and `FaceToNameService.java:382-391,366-368`
  (trim, `findByName`, insert). **High effort payoff, mostly mechanical.**
- [ ] **Extract an `EmbeddingMath`/`FaceSelector` utility** — "representative = face
  closest to the average embedding" is reimplemented 4×: `ClusteringService.pickRepresentative`
  (`ClusteringService.java:226-240`), `DeduplicationService.loadNamesWithAverages` argmax
  (`DeduplicationService.java:263-291`), `ViewService.findRepresentative`
  (`ViewService.java:337-351`), `FaceToNameService.mostSimilarToAverage`
  (`FaceToNameService.java:246-257`). **High effort payoff, mostly mechanical.**
- [ ] **`FaceDao` column-list constant** — the same 10-column SELECT is spelled out 6×
  (`FaceDao.java:75,110,157,175,193,207`); a named constant (with a test asserting the
  column count matches `mapRow`) stops silent drift. **Low effort.**
- [ ] **`NameDao` query dedup** — the correlated `face_count` subquery + name-row mapping
  is duplicated 3× (`NameDao.java:45,61,78-81`). **Low effort.**
- [ ] **Single source of truth for video extensions** — `VideoImportService.SUPPORTED_EXTENSIONS`
  (`VideoImportService.java:48-51`) and `FfmpegVideoFrameSource.demuxerName`
  (`FfmpegVideoFrameSource.java:163-180`) are two parallel extension tables that can drift
  (they agree today: mp4/m4v, mkv, mov, avi, webm, flv, mpg/mpeg, 3gp, ts, wmv). **Low effort.**

---

## 7. Bugs & small UX issues (deferred from the first pass)

- [ ] **`ORDER BY RANDOM()` scan** — `FaceDao.findRandomUnnamed` still scans + sorts the
  whole table (`FaceDao.java:158`). Deferred because the path filter restricts via EXISTS
  subqueries, so offset-based sampling would complicate the query for little gain; revisit
  if `findRandomUnnamed` ever becomes a hotspot. **Low effort.**
- [ ] **Inline hex → CSS** — ~20 `setStyle` sites with 16 hex literals
  (`FaceUi.java:200,203`, `ViewView.java:229-272`, `TagWithNameDialog.java:103,165,170`,
  `NameFaceView.java:111,332`, ...) while `styles.css` already defines matching-but-unused
  classes (`.name-label`, `.count-label`, `.name-card`, ...). Pure-presentation change,
  no functional benefit. **Medium effort, purely cosmetic.**

---

## 8. Configuration & integrations (deferred from the first pass)

- [ ] **`FaceAiService.DEVICE` in Settings** — currently hardcoded `"CPU"`
  (`FaceAiService.java:28,170`). Waiting on the `faceai` library: its DJL `Criteria`
  builders ignore `device()`, so exposing the option now would be a visible no-op. Add
  device support upstream first, then a config field wired through `FaceAiService.toFaceAIConfig`.
  **Low–Medium effort once unblocked.**
- [ ] **Hardcoded integrations** — `FeedbackService.ACCESS_KEY`/`SUBMIT_URL`
  (`FeedbackService.java:29,34`) and `UpdateChecker`'s `CHANGELOG_URL`/`CHECK_INTERVAL`
  (`UpdateChecker.java:50,56`) stay in code. The access key is a public client-side form
  key (safe by design) and rotating it would need a config UI or JSON key — revisit only
  if key rotation becomes a real workflow. **Low priority.**

---

## 10. Documentation sync

- [ ] **Translated READMEs lack video support** — `README.md` documents video import, but
  `README.de.md`, `README.es.md`, `README.fr.md`, `README.ru.md`, `README.zh.md` have zero
  mentions of "video". Each needs the video-import section added. **Low effort.**
- [ ] **AGENTS.md doc-sync rule is English-only** — `AGENTS.md:10-19` lists `todo.txt`,
  `CHANGELOG.md` and `README.md` but not the five translated READMEs, which are part of the
  deliverable and can drift out of sync. **Low effort.**
- [ ] **Stale claims in `todo.txt` item 3** — the video-import progress note still claims
  "Final full suite green 235/235" and "pushed the full matrix (run 44…)" (suite is now
  306), and the progress docs contain the "tagging facces" typo at `todo.txt:5`. **Low effort.**
- [ ] **Stray paste in `CHANGELOG.md`** — line 177 is a verbatim paste of todo.txt item 16
  appended after the last "Fixed" bullet with no heading; fold it into the proper section
  or delete it. **Low effort.**
- [ ] **Undocumented changelog-format coupling** — `README.md:167` describes the update
  cache, but the coupling to the exact `## [version]` heading regex in `UpdateChecker.VERSION_PATTERN`
  (`UpdateChecker.java:58`) is undocumented; worth a note if a future release changes the
  changelog format. **Low effort.**

---

## 11. Feature ideas

- [ ] **GPU inference option** — expose `FaceAiService.DEVICE` in Settings (see §8; blocked
  on `faceai` supporting `device()`). Would speed up clustering/import on CUDA machines.
  **Medium effort once unblocked.**
- [ ] **Configurable import budgets** — thumbnail JPEG quality (`Thumbnailer.JPEG_QUALITY = 0.85f`,
  `Thumbnailer.java:16`), max frames per video (`FrameSampler.MAX_FRAMES_PER_VIDEO = 120`,
  `FrameSampler.java:17`), face-crop size (`FaceDetectionUtils.SUB_IMAGE_MAX_DIM = 160`,
  `FaceDetectionUtils.java:32`) — move to `ConfigModel` (only `thumbnailSize` is configurable today).
  **Medium effort.**
- [ ] **Delete image/video feature** — the unused `ImageDao.delete`/`VideoDao.delete` were
  removed in the dead-code cleanup (suggestion 6), so a delete UI now also means re-adding
  those DAO methods; the FK cascade on faces remains in place as the foundation. **Medium effort.**
- [ ] **Per-name search/filter** in the browse tab — `ViewView` lists all names; a text filter
  + count badge would scale past hundreds of names. **Medium effort.**
- [ ] **Release-attached auto-update** — switch `UpdateChecker` from parsing `CHANGELOG.md`
  (`VERSION_PATTERN`, `UpdateChecker.java:58`) to the GitHub Releases API, which already
  generates release notes in CI (`softprops/action-gh-release` with `generate_release_notes: true`,
  `.github/workflows/build.yml:108-114`). **Medium effort.**
- [ ] **Build caching in CI** — every job re-clones and rebuilds FaceAI, rawGitHubFetcher and
  ffmpeg4j from scratch (`.github/workflows/build.yml:19-35,71-87`); an `actions/cache` on
  `~/.m2` would cut minutes per job. **Low effort, high payoff for CI time.**