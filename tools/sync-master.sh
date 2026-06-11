#!/usr/bin/env bash
# tools/sync-master.sh -- thin bash wrapper for sync-master.ps1
#
# Lets a git-bash / WSL user invoke the sync orchestrator without manually
# launching PowerShell. The script itself (sync-master.ps1) is the real
# implementation; this is just a launcher.
#
# Usage:
#   tools/sync-master.sh                # sync current checkout to master
#   tools/sync-master.sh -PruneWorktrees # auto-remove clean stale worktrees
#   tools/sync-master.sh -DryRun         # show what would happen
#   tools/sync-master.sh -Branch develop # sync to develop instead
#
# Exit codes: same as sync-master.ps1
#   0 = synced / no work needed
#   1 = needs human decision (dirty tree, renormalize pending, etc.)
#   2 = git failure (fetch / merge / checkout)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PS_SCRIPT="$SCRIPT_DIR/sync-master.ps1"

# Convert Unix-style path (/x/src/...) to Windows-style (X:\src\...) when we're
# running under Git for Windows or WSL. On native Linux there's no cygpath; fall
# back to the as-is path (the script will still work if PowerShell is on PATH).
if command -v cygpath >/dev/null 2>&1; then
    PS_SCRIPT="$(cygpath -w "$PS_SCRIPT")"
fi

if ! command -v powershell.exe >/dev/null 2>&1; then
    echo "tools/sync-master.sh: powershell.exe not found on PATH" >&2
    echo "  (This wrapper is for Windows / Git Bash. Run sync-master.ps1 directly" >&2
    echo "   from PowerShell on other platforms.)" >&2
    exit 127
fi

# -NoProfile : don't load the user's PowerShell profile (faster, more deterministic)
# -ExecutionPolicy Bypass : unsigned local script; no prompt for execution policy
exec powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PS_SCRIPT" "$@"
