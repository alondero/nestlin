# Nestlin agent guide

This file is for coding agents working in the repository. It complements `CLAUDE.md`; it does not replace the human-facing documentation.

## Read order

1. `CLAUDE.md` for the project context, current emulator invariants, local-only context, and established commands.
2. `docs/README.md` to choose the right source-of-truth page.
3. `docs/DEVELOPMENT.md`, `docs/ARCHITECTURE.md`, and `docs/TESTING_STRATEGY.md` for implementation work.
4. `.claude/skills/` when a task matches a project workflow such as mapper work, Mesen2 comparison, or replay reproduction.
5. `CLAUDE.local.md` only when it exists and only for machine-local paths or environment settings. Never copy its contents into tracked files.

## Working agreement

- Inspect `git status --short` before editing. Preserve existing user changes and do not reset, checkout, or delete unrelated work.
- Stay inside the active worktree. Do not write to the parent repository or another worktree.
- Make the smallest coherent change. Follow existing Kotlin, Gradle, PowerShell, shell, and line-ending conventions.
- Diagnose before fixing reported bugs; for emulator behavior, add the regression test before or with the fix when practical.
- For mapper, timing, rendering, or input changes, run the relevant oracle-free or structured comparison evidence. A green fast suite is not proof that an external-ROM game boots.
- Do not obtain, commit, or ask users to upload copyrighted ROMs, BIOS files, credentials, tokens, or private logs.
- Do not claim a command passed unless it ran. Report skipped tests, missing optional dependencies, and environment limitations explicitly.

## Documentation-first contract

Documentation is part of the implementation:

- User-visible behavior, controls, flags, and files go in `docs/USER_GUIDE.md` or `docs/TROUBLESHOOTING.md`.
- Mapper/game compatibility goes in `MAPPER_SUPPORT.md` with evidence and limits.
- Architecture and invariants go in `docs/ARCHITECTURE.md`, `CONTEXT.md`, or an ADR.
- Build, test, tool, and hook changes go in `docs/DEVELOPMENT.md` and `docs/DOCUMENTATION_STANDARDS.md`.
- Release/native changes go in `docs/RELEASING.md`, `native/README.md`, or `RA_INTEGRATION.md`.
- Link every new Markdown document from `docs/README.md` or its GitHub entry point.
- Run `python tools/docs_lint.py` for documentation changes. Use `./gradlew docsLint` when working through Gradle.

## Verification and handoff

Before reporting completion:

1. Run the smallest relevant check, then the fast suite when code changed.
2. Run the documentation linter when docs/configuration changed.
3. Review `git diff --check` and `git status --short`.
4. Summarize files changed, commands actually run, skipped checks, and any remaining risk.

The repository's optional hooks enforce staged documentation checks and Conventional Commits. Install them with `tools/install-hooks.ps1` or `tools/install-hooks.sh`; do not assume they are active in every checkout.
