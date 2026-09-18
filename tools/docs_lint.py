#!/usr/bin/env python3
"""Small dependency-free checks for Nestlin's maintained documentation surface."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote


# Repo-tree locations that the linter must never walk. These directories
# hold transient, machine-local, or third-party content that is not part
# of the published documentation surface. Entries are matched as directory
# prefixes relative to ROOT.
EXCLUDED_DIRS = (
    ".git",
    ".gradle",
    "build",
    "native/rcheevos",
    # Coding-agent session notes, plans, and scratchpads live under
    # .claude/commands and .claude/skills; managed worktrees live under
    # .claude/worktrees. None of these are intended to be documentation.
    ".claude/commands",
    ".claude/skills",
    ".claude/worktrees",
)

# Canonical mapper dispatch source and the documentation that must agree
# with it. Keep these in sync with the table in
# docs/DOCUMENTATION_STANDARDS.md.
GAMEPAK_PATH = "src/main/kotlin/com/github/alondero/nestlin/gamepak/GamePak.kt"
MAPPER_DOC_PATH = "MAPPER_SUPPORT.md"

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = (
    "README.md",
    "CONTRIBUTING.md",
    "CODE_OF_CONDUCT.md",
    "CLAUDE.md",
    ".claude/agents.md",
    ".github/SECURITY.md",
    ".github/PULL_REQUEST_TEMPLATE.md",
    ".github/ISSUE_TEMPLATE/bug_report.md",
    ".github/ISSUE_TEMPLATE/compatibility_report.md",
    ".github/ISSUE_TEMPLATE/feature_request.md",
    ".github/ISSUE_TEMPLATE/regression_report.md",
    "docs/README.md",
    "docs/USER_GUIDE.md",
    "docs/TROUBLESHOOTING.md",
    "docs/DEVELOPMENT.md",
    "docs/ARCHITECTURE.md",
    "docs/DOCUMENTATION_STANDARDS.md",
    "docs/RELEASING.md",
    "docs/TESTING_STRATEGY.md",
    "MAPPER_SUPPORT.md",
)

MARKDOWN_LINK = re.compile(
    r"!?\[[^\]]*\]\(\s*(?:<([^>]+)>|([^\s)]+))(?:\s+[^)]*)?\)"
)
H1 = re.compile(r"^#\s+\S")
MAPPER_DISPATCH = re.compile(r"^\s+(\d+)\s+->\s+Mapper", re.MULTILINE)
MAPPER_HEADING = re.compile(r"^##\s+Mappers?\s+((?:\d+\s*,\s*)*\d+)", re.MULTILINE)


def markdown_files() -> list[Path]:
    excluded = EXCLUDED_DIRS
    files: list[Path] = []
    for path in ROOT.rglob("*.md"):
        relative = path.relative_to(ROOT).as_posix()
        if relative == "AGENTS.md":
            # AGENTS.md is a repository symlink target pointer on platforms
            # where Git cannot materialise symlinks; it is not a Markdown page.
            continue
        if any(relative == item or relative.startswith(item + "/") for item in excluded):
            continue
        files.append(path)
    return sorted(files)


def headings_outside_fences(text: str) -> int:
    # Track an explicit stack of {fence_char, fence_len} so nested code
    # blocks (e.g. a 4-backtick wrapper around a 3-backtick example)
    # toggle correctly and Markdown's "closing fence must be at least as
    # long as the opener" rule is honoured.
    fence_stack: list[tuple[str, int]] = []
    count = 0
    for line in text.splitlines():
        stripped = line.lstrip()
        if stripped.startswith(("```", "~~~")):
            opener = stripped[0]
            opener_len = len(stripped) - len(stripped.lstrip(opener))
            if fence_stack and fence_stack[-1][0] == opener and opener_len >= fence_stack[-1][1]:
                # Closing fence: pop the matching opener.
                fence_stack.pop()
            else:
                fence_stack.append((opener, opener_len))
            continue
        if not fence_stack and H1.match(line):
            count += 1
    return count


def local_link_target(source: Path, raw_target: str) -> tuple[Path | None, str | None]:
    target = unquote(raw_target.strip())
    if not target or target.startswith("#"):
        return None, None
    if re.match(r"^(?:[a-z][a-z0-9+.-]*:|//)", target, re.IGNORECASE):
        return None, None

    target = target.split("#", 1)[0].split("?", 1)[0]
    if not target:
        return None, None
    if "\\" in target:
        return None, f"uses backslashes: {raw_target}"

    candidate = (source.parent / target).resolve()
    try:
        candidate.relative_to(ROOT)
    except ValueError:
        return None, f"escapes the repository: {raw_target}"

    relative = candidate.relative_to(ROOT).as_posix()
    if relative == "build" or relative.startswith("build/"):
        return None, f"points into build output: {raw_target}"
    return candidate, None


def check() -> list[str]:
    errors: list[str] = []

    for relative in REQUIRED:
        if not (ROOT / relative).is_file():
            errors.append(f"missing required documentation file: {relative}")

    gamepak = ROOT / GAMEPAK_PATH
    mapper_doc = ROOT / MAPPER_DOC_PATH
    if gamepak.is_file() and mapper_doc.is_file():
        source_ids = {int(value) for value in MAPPER_DISPATCH.findall(gamepak.read_text(encoding="utf-8"))}
        documented_ids = {
            int(value)
            for heading in MAPPER_HEADING.findall(mapper_doc.read_text(encoding="utf-8"))
            for value in re.findall(r"\b\d+\b", heading)
        }
        if source_ids != documented_ids:
            errors.append(
                "MAPPER_SUPPORT.md mapper IDs do not match GamePak.createMapper(): "
                f"missing={sorted(source_ids - documented_ids)}, "
                f"undispatched={sorted(documented_ids - source_ids)}"
            )

    for path in markdown_files():
        relative = path.relative_to(ROOT).as_posix()
        data = path.read_bytes()
        if not data.endswith(b"\n"):
            errors.append(f"{relative}: file must end with a newline")

        text = data.decode("utf-8")
        heading_count = headings_outside_fences(text)
        if heading_count != 1:
            errors.append(f"{relative}: expected exactly one level-one heading, found {heading_count}")

        for match in MARKDOWN_LINK.finditer(text):
            raw_target = match.group(1) or match.group(2) or ""
            candidate, error = local_link_target(path, raw_target)
            if error:
                errors.append(f"{relative}: {error}")
            elif candidate is not None and not candidate.exists():
                errors.append(f"{relative}: broken local link: {raw_target}")

    return errors


def main() -> int:
    errors = check()
    if errors:
        print("Documentation lint failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"Documentation lint passed ({len(markdown_files())} Markdown files checked).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())