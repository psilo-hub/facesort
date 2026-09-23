# AGENTS.md

Guidelines for AI coding agents working in this repository.

## Before committing

After every verified change (passing build and tests), update the relevant
files **in the same commit** that contains the change:

1. `todo.txt` — keep it in sync with the project's current state. Mark completed
   items, remove finished tasks, and add newly discovered work items.

2. `src/main/resources/CHANGELOG.md` — record user-visible changes under the
   current `[1.0-SNAPSHOT]` heading. Use the existing format: `### Added`,
   `### Changed`, `### Fixed`, etc. Keep entries concise and user-facing.

3. `README.md` — reflect any change that affects how the app is built, run,
   configured, or used (new features, changed behavior, new settings, altered
   build steps).

4. The translated READMEs — `README.de.md`, `README.es.md`, `README.fr.md`,
   `README.ru.md` and `README.zh.md` are part of the deliverable too: any change
   that touches `README.md` (per item 3) must also be reflected in all five
   translations, so they cannot drift out of sync with the English original.

These files are part of the deliverable, not afterthoughts. Do not omit them.

## Workflow

- Follow **Test-Driven Development (TDD)**: write a failing test first, watch it
  fail, implement the minimal change to make it pass, then refactor.
- Run the test suite after every change and confirm it is green before
  committing:
  ```bash
  mvn test
  ```
- Keep changes small and atomic. Prefer a focused commit per task.
- Only commit when explicitly asked, but when asked, include the docs updates
  above in that commit.

## Code principles

- **Clean code**: follow the existing structure and naming conventions, keep
  methods small and focused, use meaningful names, avoid duplication.
- Do not add comments unless they clarify non-obvious intent; keep existing
  Javadoc style when writing public API docs.
- Reuse existing utilities and patterns (DAO layer, services, config handling,
  `Task`-based background work on the UI thread, tooltips for settings).
- Prefer composition, immutable data (records) and clear test seams over
  hard-coded dependencies.
- Never ship code that logs or embeds secrets.
- Do not break backward compatibility of the config file without a documented
  migration.