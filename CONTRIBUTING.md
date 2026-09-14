# Contributing to Nestlin

This page is for contributors opening issues, pull requests, or documentation changes. Nestlin is a personal learning project, so contribution scope and review capacity are limited; small, focused changes with reproducible evidence are easiest to review.

## Before opening an issue or pull request

- Search existing issues and read the [troubleshooting guide](docs/TROUBLESHOOTING.md).
- Read the [Code of Conduct](CODE_OF_CONDUCT.md) before participating.
- Do not upload copyrighted ROMs, BIOS files, credentials, or private logs. Identify a dump with its filename, mapper/submapper, region, and checksum instead.
- For security or credential exposure, follow [SECURITY.md](.github/SECURITY.md).

## Pull request expectations

1. Explain the problem, the intended behavior, and the smallest useful scope.
2. Add a regression test for a bug when practical. For emulator behavior, prefer structured state evidence to screenshots alone.
3. Run the relevant Gradle test lane and `python tools/docs_lint.py`; report exact commands and any skipped optional lanes.
4. Update the source-of-truth documentation for changed user behavior, compatibility, commands, or invariants.
5. Keep generated files, ROMs, save data, credentials, and local paths out of the commit.
6. Use a Conventional Commit title such as `fix(ppu): preserve vblank latch during pre-render`.

The pull-request template is a checklist, not a substitute for a clear description. A human author remains responsible for reviewing and understanding all submitted code and documentation. If an agent or other AI tool materially assisted, disclose that in the PR description and verify every stated result.

## Development references

- [Development guide](docs/DEVELOPMENT.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Testing strategy](docs/TESTING_STRATEGY.md)
- [Mapper support and checklist](MAPPER_SUPPORT.md)
- [Documentation standards](docs/DOCUMENTATION_STANDARDS.md)
- [Agent guide](.claude/agents.md)
