# Nestlin documentation

Nestlin is a learning-focused NES emulator. This directory separates the information needed by players, bug reporters, contributors, and automation from the repository's machine-local notes.

## Start here

- [User guide](USER_GUIDE.md) — install, launch, controls, configuration, ROM formats, saves, and RetroAchievements.
- [Troubleshooting](TROUBLESHOOTING.md) — common launch, input, audio, rendering, mapper, and test-environment failures.
- [Mapper support](../MAPPER_SUPPORT.md) — the compatibility matrix, known quirks, and the new-mapper checklist.

## Develop here

- [Development guide](DEVELOPMENT.md) — architecture tour, prerequisites, build commands, test lanes, and debugging workflow.
- [Architecture](ARCHITECTURE.md) — subsystem boundaries and the code paths that own emulator state.
- [Testing strategy](TESTING_STRATEGY.md) — the state-diff test pyramid and regression-test rules.
- [Contributing](../CONTRIBUTING.md) — contribution, issue, pull-request, and documentation expectations.
- [Code of Conduct](../CODE_OF_CONDUCT.md) — respectful participation and reporting boundaries.
- [Releasing](RELEASING.md) — release artifacts, native libraries, validation, and rollback notes.
- [Documentation standards](DOCUMENTATION_STANDARDS.md) — what must be documented and how the docs are linted.

## Design and research notes

- [Domain language](../CONTEXT.md) — project terms and ownership boundaries.
- [Architecture decision records](adr/) — decisions that should remain durable after an implementation changes.
- [Research](research/) — cited comparisons and other time-sensitive investigations.
- [Historical design notes](PPU_RENDERING_PLAN.md) and [Donkey Kong rendering notes](DONKEY_KONG_RENDERING_PLAN.md) — milestone notes retained for context, not current status.
- [Native RetroAchievements integration](../RA_INTEGRATION.md) — design, build, and runtime fallback details.
- [Manual RA acceptance script](MANUAL_RA_ACCEPTANCE.md) — per-OS validation steps for the RetroAchievements integration.

## Source-of-truth rule

User-visible behavior belongs in the [user guide](USER_GUIDE.md) or [troubleshooting guide](TROUBLESHOOTING.md). Build, test, and code-ownership rules belong in the [development guide](DEVELOPMENT.md) or [contributor guide](../CONTRIBUTING.md). Current mapper compatibility belongs in [`MAPPER_SUPPORT.md`](../MAPPER_SUPPORT.md). `CLAUDE.md` and `.claude/agents.md` are navigation and automation guidance for coding agents; they must link to, not replace, the human-facing documentation.

When a change affects behavior, update the relevant source-of-truth document in the same change. The documentation linter checks local links, required entry points, heading structure, and final newlines in CI and through the optional pre-commit hook.
