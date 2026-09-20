# Task: Implement unchecked items from VIDEO_SUPPORT.md

You are working in the `psilo-hub/facesort` repository. Your job on this
invocation is to implement unchecked items from the implementation plan in
`VIDEO_SUPPORT.md`, verify them, update all affected documentation, and
commit. This instruction is designed to be run repeatedly — each run
advances the plan — until every phase in `VIDEO_SUPPORT.md` is fully
checked off.

**Important:** After committing each item, check whether the current phase
still has unchecked items. If it does, continue with the next item (still
committing after each one) rather than stopping. Only stop when the
entire phase is complete or when no unchecked items remain in the file.

## Hard rules

1. **One phase per run, items committed after each.** Implement items in the
   first phase that still has unchecked work, in document order. After
   committing each item, check whether the phase still has unchecked items.
   If it does, continue with the next item (committing after each one) rather
   than stopping. Only stop when the entire phase is complete or when no
   unchecked items remain in the file.
2. **Read before writing.** Read `VIDEO_SUPPORT.md` in full, then read
   every source file the item touches before editing anything.
3. **Follow the plan.** `VIDEO_SUPPORT.md` is the source of truth for
   schema, class names, constants, and library choices. Do not invent
   alternatives (e.g., a different ffmpeg binding, a different table
   layout) unless the plan explicitly leaves it open.
4. **Match existing conventions.** Mirror the project's package layout,
   naming, error handling, logging, i18n keys, and test style. When in
   doubt, copy the pattern from the nearest analogous existing feature.
5. **Do not commit broken code.** The build and test suite must pass
   before the commit. If you cannot make it pass, stop and report — do
   not commit.
6. **Do not fabricate.** If the item is ambiguous or blocked by missing
   context, stop and ask rather than guessing.

## Procedure

### Step 1 — Locate the next item

1. Open `VIDEO_SUPPORT.md`.
2. Scan phases in order (Phase 1, 2, 3, …).
3. Within the first phase that still has unchecked work, find the
   **first** unchecked checkbox (`- [ ]`).
4. Note its:
   - phase number and title,
   - exact checkbox text,
   - any sub-bullets or notes immediately beneath it that scope the work.
5. If any sub-checkboxes exist under that item, treat the first
   unchecked *sub-item* as the unit of work for this item.
6. The phase chosen here is the **current phase** for this run; you will
   keep working through its unchecked items until the phase is complete.

If **no unchecked items remain** anywhere in the file: stop, print
"VIDEO_SUPPORT.md is fully implemented — nothing to do." and exit
without committing.

### Step 2 — Recon

Before editing:

- Read the files the item names or implies. If the item doesn't name
  files, grep the repo for related symbols (table names, class names,
  constants from the plan) to find the right touch points.
- Read `CHANGELOG.md` to learn the current entry format and the
  `Unreleased` section layout.
- Read `README.md` (and any other README variants, e.g.
  `README_*.md`) to see whether the item changes user-facing behaviour,
  setup steps, dependencies, or supported formats.
- Read `pom.xml` (or equivalent) if the item touches dependencies,
  build steps, or CI.

### Step 3 — Implement

Implement the item exactly as scoped by `VIDEO_SUPPORT.md`:

- Keep the change as small as the item allows. Do not opportunistically
  refactor unrelated code.
- Add or update tests at the same level of coverage the project already
  uses for analogous features.
- Add i18n keys for any new user-visible strings, in every locale the
  project ships.
- If the item adds a new dependency or build step, update CI config and
  the build file in the same commit.
- If the item is partially done already, complete only the missing
  part.

### Step 4 — Verify

Run, in this order, and fix anything that fails:

1. Build: `mvn -q -DskipTests package` (or the project's documented
   build command).
2. Tests: `mvn -q test` (or the documented test command).
3. Any linter / formatter / static analysis the project configures.
4. If the item is user-visible and the project has a way to smoke-test
   it, do a manual sanity pass and note the result.

If verification cannot pass after reasonable effort, **stop**. Do not
check the box, do not update the changelog, do not commit. Report what
failed and what you tried.

### Step 5 — Update `VIDEO_SUPPORT.md`

1. Change the item's `- [ ]` to `- [x]`.
2. If the phase now has no unchecked items, tick the phase heading's
   checkbox too (if it has one).
3. Do not reword the item, renumber phases, or restructure the file.
4. If implementation diverged from the plan in a way future readers
   need to know, append a short `> Note:` line beneath the item — do
   not rewrite the item itself.

### Step 6 — Update `CHANGELOG.md`

- Add an entry under the existing `Unreleased` section (create the
  section only if the file genuinely lacks one).
- Use the file's existing style. If it follows Keep a Changelog, group
  under the correct heading (`Added`, `Changed`, `Fixed`, `Removed`)
  and write from the user's perspective.
- Reference the phase/item, e.g. `- Added video frame extraction
  pipeline (VIDEO_SUPPORT.md Phase 3).`
- Do not invent a version number or date.

### Step 7 — Update READMEs if necessary

Update `README.md` (and any other README that documents the affected
area) only if the item changes something a reader of that README needs
to know, such as:

- new/changed user-facing features,
- new dependencies or system requirements (e.g., ffmpeg),
- new supported file formats,
- new setup, build, or run steps,
- new configuration options.

If nothing user-facing changed, leave the READMEs untouched — do not
manufacture edits.

### Step 8 — Commit

1. Stage only the files this item touched:
   - the implementation files,
   - tests,
   - `VIDEO_SUPPORT.md`,
   - `CHANGELOG.md`,
   - READMEs (if changed),
   - build/CI files (if changed).
2. Do **not** stage unrelated working-tree changes; if any exist,
   leave them unstaged and mention them in the final report.
3. Commit with a message in this exact shape:

   ```
   <phase>: <short imperative summary>

   Implements VIDEO_SUPPORT.md Phase <N>: <exact checkbox text>.

   - <bullet: what changed>
   - <bullet: tests added/updated>
   - <bullet: docs updated>
   ```

   Keep the subject line under ~72 characters. Use the imperative mood.
4. Do not amend, rebase, force-push, or push to a remote unless the
   user explicitly asked you to.
5. After the commit, check whether the current phase still has unchecked
   items:
   - **If yes:** return to **Step 1** and implement the next item in the
     same phase (commit after each item). Do not stop between items.
   - **If no:** the phase is complete. Proceed to Step 9 to report the
     whole run.

### Step 9 — Report

Print a concise report for the full run (whole phase):

- Phase completed and list of items completed (phase + exact text for each).
- Files changed (per item).
- Verification results (build, tests, manual checks).
- Remaining unchecked items in `VIDEO_SUPPORT.md` (count + next item).
- Anything skipped, blocked, or left unstaged.

## Stop conditions

The normal stopping point is **the end of the run's phase** (all items in
the current phase committed and checked off, each in its own commit). Stop
earlier — immediately — and ask the user if:

- `VIDEO_SUPPORT.md` is missing or unreadable.
- The next unchecked item is ambiguous about scope, schema, or library.
- The item requires a dependency, license, or secret you cannot add
  safely.
- Verification fails and the fix is non-obvious.
- The working tree has unrelated changes that would be entangled with
  this commit.

When an early stop happens mid-phase, report what was completed so far,
what is blocked, and where the next run should resume.

## What "done" looks like for the whole plan

Running this instruction repeatedly is complete when
`VIDEO_SUPPORT.md` contains no unchecked boxes in any phase. At that
point every phase's work is implemented, tested, documented in
`CHANGELOG.md`, reflected in the READMEs where relevant, and committed.
Each item is committed as its own focused commit; a single run does not
stop until it has finished every item in its current phase.
