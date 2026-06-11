#!/usr/bin/env bash
# tools/tests/run-tests.sh -- bash test runner for sync-master.Tests.ps1
#
# Wraps the Pester invocation so a Git-Bash / WSL user can run the suite
# with one command, matching the experience of `./gradlew test`.
#
# Usage:
#   tools/tests/run-tests.sh
#
# Exit codes match Pester's convention:
#   0 = all tests passed
#   1 = at least one test failed
#   other = invocation error

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PS_SCRIPT="$SCRIPT_DIR/sync-master.Tests.ps1"

if command -v cygpath >/dev/null 2>&1; then
    PS_SCRIPT_WIN="$(cygpath -w "$PS_SCRIPT")"
else
    PS_SCRIPT_WIN="$PS_SCRIPT"
fi

if ! command -v powershell.exe >/dev/null 2>&1; then
    echo "run-tests.sh: powershell.exe not found on PATH" >&2
    exit 127
fi

# Pester 3.4's Invoke-Pester returns 0 on all-green, 1 on any failure.
# We forward that exit code through `set -e` -> the script's exit code.
exec powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Invoke-Pester -Script '$PS_SCRIPT_WIN'"
