<#
PowerShell Git commit-msg hook enforcing Conventional Commits standard.
Usage: powershell -File .githooks/commit-msg.ps1 <path-to-commit-msg-file>
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$CommitMsgFile
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $CommitMsgFile)) { exit 0 }

$firstLine = (Get-Content -Path $CommitMsgFile -TotalCount 1).Trim()

if ($firstLine -match '^Merge\s+' -or $firstLine -match '^Revert\s+') {
    exit 0
}

$conventionalPattern = '^(feat|fix|docs|refactor|test|chore|perf|style|build|ci)(\([a-z0-9_\-\.\/]+\))?(!)?:\s+[^\s@].+$'

if ($firstLine -notmatch $conventionalPattern) {
    [Console]::Error.WriteLine("ERROR: Invalid commit message format.")
    [Console]::Error.WriteLine("Rejected subject: '$firstLine'")
    [Console]::Error.WriteLine("Expected format: <type>(<optional-scope>): <description>")
    [Console]::Error.WriteLine("Allowed types: feat, fix, docs, refactor, test, chore, perf, style, build, ci")
    [Console]::Error.WriteLine("Example: fix(apu): defer frame counter reset by get-put phase (Closes #297)")
    exit 1
}

exit 0
