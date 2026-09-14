# Documentation standards

Documentation is a maintained interface for players, contributors, and automation. A change is incomplete when its behavior or workflow cannot be found by the next person using the project.

## Required coverage

Every change should answer the applicable questions below:

| Change | Required documentation |
| --- | --- |
| User-visible feature, control, file, or flag | User guide entry, including defaults and platform caveats. |
| Game or mapper compatibility | Mapper support entry with exact mapper/submapper, game/dump identity, region, evidence, and known limits. Never claim “works” from a unit test alone. |
| Bug fix or regression | Reproduction and verification command in the test or troubleshooting notes when the workflow is reusable. |
| Architecture, timing, or ownership invariant | Architecture update, glossary update, or ADR with the reason and consequences. |
| Build, test, hook, or CI change | Development guide and, if user-facing, the relevant README or contributor guide. |
| Release or native dependency change | Releasing/native/RA documentation, including fallback and rollback behavior. |
| New tool or script | `--help`/usage example, owner, input/output contract, exit codes, and a link from the docs index. |

## Writing rules

- State who the page is for in its opening paragraph.
- Put the shortest successful command near the top.
- Distinguish supported, experimental, stubbed, skipped, and untested behavior.
- Give exact paths, flags, environment variables, and exit codes when a reader must act on them.
- Never ask users to upload copyrighted ROMs, BIOS files, credentials, tokens, or private logs.
- Prefer a source-of-truth page over copying the same table into several documents.
- For hardware facts and compatibility claims, link the upstream specification, reference implementation, or local regression evidence.
- Mark time-sensitive facts with a date or a clear “current as of” note.
- Keep historical plans clearly labeled as historical; do not link them as current implementation status.
- Agents must not claim a command passed unless it was actually run. Record skips and missing optional dependencies explicitly.

## Source-of-truth map

- `README.md`: project landing page and links for a first-time visitor.
- `docs/USER_GUIDE.md`: using Nestlin.
- `docs/TROUBLESHOOTING.md`: diagnosing and reporting failures.
- `docs/DEVELOPMENT.md`: building, testing, and changing Nestlin.
- `docs/ARCHITECTURE.md`: subsystem ownership and invariants.
- `MAPPER_SUPPORT.md`: current mapper/game support.
- `CONTEXT.md`: domain vocabulary.
- `CLAUDE.md` and `.claude/agents.md`: coding-agent navigation and guardrails.

If two pages disagree, update the source-of-truth page first, then remove or replace the stale duplicate rather than adding another exception.

## Automated checks

Run:

```bash
python tools/docs_lint.py
```

`docsLint` exposes the same check through Gradle. CI runs it on documentation/configuration changes and the optional pre-commit hook runs it for staged Markdown/YAML changes. The linter currently enforces:

- all required documentation entry points exist;
- every Markdown document has exactly one level-one heading;
- local Markdown links and images resolve to tracked or present files;
- Markdown files end with a newline;
- no link points into ignored build output.

The linter deliberately does not impose a universal line length, prose style, or spelling dictionary. Those checks create noisy churn in technical emulator notes and should be added only with a repository-wide migration plan.
