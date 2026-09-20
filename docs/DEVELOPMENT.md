# Development guide

This page is for contributors and maintainers setting up a checkout, changing emulator code, or validating a release-related change.

## Prerequisites

- JDK 21.
- Gradle wrapper support; use `gradlew.bat` on Windows and `./gradlew` on Unix-like shells.
- Python 3 for ROM inspection and documentation linting.
- Optional Mesen2 installation and external test ROMs for oracle comparisons. Do not commit commercial ROMs.
- Optional C compiler for the native RetroAchievements façade.

The machine-specific paths and environment variables belong in the ignored `CLAUDE.local.md`. Keep repository-wide behavior in tracked documentation instead.

## First checkout

```bash
git clone <repository-url>
cd nestlin
./gradlew test
python tools/docs_lint.py
```

Install the repository hooks once if you want documentation checks before commits:

```bash
./tools/install-hooks.sh
```

PowerShell users can run `tools/install-hooks.ps1`. The hook only blocks a commit when staged documentation/configuration files fail the same linter used by CI.

## Build and test commands

| Command | Purpose | Needs external assets |
| --- | --- | --- |
| `./gradlew build` | Compile and run the hermetic test suite. | No Mesen2 or external ROM. |
| `./gradlew test` | Fast JUnit lane; excludes `mesen`, `externalRom`, and `nativeRa` tags. | No. |
| `./gradlew testMesenComparison` | Run Mesen2-tagged structured comparisons. | Mesen2 and configured ROMs where a test requires them. |
| `./gradlew testNativeRa` | Run the native RA contract tests. | Local C-built native library. |
| `./gradlew docsLint` | Validate documentation links and structure. | Python 3. |
| `./gradlew bootcheck -Prom=<rom>` | Oracle-free real-ROM boot smoke. | A legally obtained ROM. |
| `./gradlew diverge -Prom=<rom> -Pframe=120` | Compare Nestlin and Mesen2 at a selected frame. | Mesen2 and ROM. |
| `./gradlew verifyTestEnv` | Print resolved Mesen2, ROM, and strict-mode settings. | No; it reports what is missing. |
| `./gradlew shadowJar` | Build the runnable fat JAR. | Optional native RA asset. |
| `./gradlew buildNative` | Build the native RA façade. | A supported C compiler. |

The headless JAR commands and exit codes are documented in [`USER_GUIDE.md`](USER_GUIDE.md) and [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md). Do not copy machine-local absolute paths into tracked docs.

## Source layout

The package map is maintained in [`ARCHITECTURE.md`](ARCHITECTURE.md). The shortest useful route for a change is usually:

1. Find the owning subsystem and its public seam.
2. Read the focused tests before changing production code.
3. Add a failing regression test for a bug.
4. Run the smallest relevant lane, then the full fast suite.
5. Update user/developer documentation if behavior, commands, compatibility, or invariants changed.

## Mapper workflow

Before mapper code, inspect the ROM header:

```bash
python tools/rom_info.py info path/to/game.nes
```

Then follow the checklist in `.claude/skills/new-mapper/SKILL.md` and update [`MAPPER_SUPPORT.md`](../MAPPER_SUPPORT.md). A mapper change is not complete until it has unit coverage, a real-game boot signal, and either Mesen2 comparison evidence or an explicit documented limitation.

## Test lanes and false-green prevention

The default suite intentionally excludes tests that require unavailable external tools. A green `test` result therefore does not prove that a real game boots. Use `verifyTestEnv`, `bootcheck`, and `testMesenComparison` when the change touches a mapper, timing, rendering, or input path. Never replace an unavailable oracle with a silent `assumeTrue` skip.

Tests use `TestRomBuilder`; do not hand-roll new 16-byte iNES headers. Prefer state diffs over pixel diffs. See [`TESTING_STRATEGY.md`](TESTING_STRATEGY.md) for the detailed rules and lint-enforced anti-patterns.

## Documentation workflow

Documentation is part of the change, not release cleanup:

- User-visible behavior: update `docs/USER_GUIDE.md` or `docs/TROUBLESHOOTING.md`.
- Compatibility: update `MAPPER_SUPPORT.md` with the mapper, game/dump identity, region, test evidence, and known limits.
- Architecture or invariants: update `docs/ARCHITECTURE.md`, `CONTEXT.md`, or an ADR.
- Build/test/tooling: update this guide and the relevant command reference.
- Release/native behavior: update `docs/RELEASING.md`, `native/README.md`, or `RA_INTEGRATION.md`.
- Every new Markdown file must be linked from [`docs/README.md`](README.md) or the appropriate GitHub entry point.

Run `python tools/docs_lint.py` before opening a pull request. The linter checks local links, required documentation entry points, one H1 per document, and final newlines.

## Documentation tooling contract

The repository maintainer owns the following small, dependency-light checks and hooks:

| Tool | Input and output | Exit codes |
| --- | --- | --- |
| `tools/docs_lint.py` | Reads the current checkout; prints failures to stderr or a passing summary to stdout. Checks required docs, headings, newlines, local links/images, and mapper-dispatch consistency. | `0` pass, `1` validation failure. |
| `./gradlew docsLint` | Runs the same Python linter through Gradle. Honors the `PYTHON` environment variable. | Gradle's success/failure code; the linter's `0`/`1` is propagated. |
| `tools/install-hooks.sh` / `.ps1` | Configures `core.hooksPath=.githooks` for the current checkout and prints the installed path. | `0` installed, non-zero if Git configuration fails. |
| `.githooks/pre-commit` / `.ps1` | Reads staged Markdown/YAML file names and runs the documentation linter when relevant files are staged. | `0` no relevant changes or pass, `1` missing Python/lint failure. |
| `.githooks/commit-msg` / `.ps1` | Reads the commit-message file supplied by Git and validates the first line as a Conventional Commit. | `0` accepted, `1` rejected. |

Hooks are opt-in and must never be treated as a substitute for CI. The authoritative check is `.github/workflows/docs.yml` plus the `check` dependency on `docsLint`.

## Lint-as-test tooling

Two complementary mechanisms keep Kotlin style and architectural conventions from regressing. Both run as part of `./gradlew test` — there is no separate "lint" task to remember.

| Layer | Tool | Lives in | Catches |
| --- | --- | --- | --- |
| AST rules | [Konsist](https://github.com/LemonAppDev/konsist) 0.13.0 | `src/test/kotlin/com/github/alondero/nestlin/testutil/KonsistArchitectureTest.kt` | Architectural and Kotlin-idiom rules: `Enum.entries` over `Enum.values()`, mapper classes in `gamepak/`, internal subsystem cross-imports. |
| Source-text patterns | Plain JUnit + regex | `src/test/kotlin/com/github/alondero/nestlin/testutil/HeaderConstructionLintTest.kt`, `MapperCoverageLintTest.kt`, `TestAssertsLintTest.kt` | Literal-byte rules that the AST does not expose: hand-built iNES headers (`ByteArray(16)`), Mesen2-test lane wiring, `kotlin.test` imports, `Assertions.fail(<string>)` overloads. These files own a shrinking-baseline pattern for grandfathered offenders. |

Why Konsist and not Detekt or ktlint: the project pins Kotlin 1.9.22; the latest Konsist line (0.17.x) embeds Kotlin 2.0.20 and would conflict. 0.13.0 matches the project's Kotlin toolchain, runs in the fast JUnit lane, and expresses rules in Kotlin next to existing tests — no YAML to maintain, no separate Gradle plugin to keep in lock-step. The trade-off is that rules whose signal is in literal source bytes (regex-shaped) stay in the older `*LintTest.kt` files; Konsist can host them via `KoFileDeclaration.text`, but doing so would lose the grandfathered-baseline migration machinery those tests use to shrink one offender at a time.

To add a new AST rule, see the "Static analysis — Konsist" bullet in `CLAUDE.md`. To add a new source-text lint, copy an existing `*LintTest.kt`, define a `RAW_*_PATTERNS` regex, an `EXCLUDED` set of files that legitimately mention the pattern (the builder, the lint itself, doc comments), and a `BASELINE` set of grandfathered offenders that must only shrink.

## Coding and commit standards

Use Kotlin idioms already present in the codebase and keep changes focused. Conventional Commits are required by the repository hooks, for example:

```text
docs: add mapper troubleshooting workflow
fix(ppu): preserve vblank latch during pre-render
```

An agent may assist with analysis or edits, but the human author is responsible for reviewing every line and reporting which checks actually ran. See [CONTRIBUTING.md](../CONTRIBUTING.md) and [`.claude/agents.md`](../.claude/agents.md).
