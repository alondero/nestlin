<#
PreToolUse hook: enforce Conventional Commits for all `git commit` and `gh pr create/edit` commands.

Why: models (and distracted humans) frequently invent malformed commit messages or PR titles with
stray characters (e.g. `@ fix(apu): ...`), non-standard types, or missing scopes/descriptions.
This hook inspects commands before execution and blocks non-conforming messages with actionable feedback.

Conventional Commit specification:
  <type>(<optional-scope>): <description>
  Allowed types: feat, fix, docs, refactor, test, chore, perf, style, build, ci

Exit codes:
  - 0: Allowed (command conforms or is not a commit/PR command)
  - 2: Blocked (Claude Code aborts execution and shows stderr to agent)
#>
$ErrorActionPreference = 'Stop'

try {
    $stdin = [Console]::In.ReadToEnd()
    if (-not $stdin) { exit 0 }
    $payload = $stdin | ConvertFrom-Json

    $cmd = $payload.tool_input.command
    if (-not $cmd) { exit 0 }

    $conventionalPattern = '^(feat|fix|docs|refactor|test|chore|perf|style|build|ci)(\([a-z0-9_\-\.\/]+\))?(!)?:\s+[^\s@].+$'

    # Helper to extract the first argument value after a given flag (-m, --message, -t, --title)
    function Extract-FlagValue([string]$command, [string[]]$flags) {
        foreach ($flag in $flags) {
            # Match --flag="value", --flag='value', --flag value, -f "value", -f 'value', -f value
            $escaped = [regex]::Escape($flag)
            $patterns = @(
                "(?:^|\s)$escaped\s*=\s*`"([^`"]+)`"",
                "(?:^|\s)$escaped\s*=\s*'([^']+)'",
                "(?:^|\s)$escaped\s*=\s*(\S+)",
                "(?:^|\s)$escaped\s+`"([^`"]+)`"",
                "(?:^|\s)$escaped\s+'([^']+)'",
                "(?:^|\s)$escaped\s+`$'([^']+)'",
                "(?:^|\s)$escaped\s+([^\s`"'-]\S*)"
            )
            foreach ($p in $patterns) {
                if ($command -match $p) {
                    return $Matches[1]
                }
            }
        }
        return $null
    }

    # 1. Check `git commit`
    if ($cmd -match '\bgit\s+commit\b') {
        # If `--amend --no-edit` without `-m`, allow
        if ($cmd -match '--amend' -and $cmd -match '--no-edit' -and $cmd -notmatch '(-m|--message)') {
            exit 0
        }

        $msg = Extract-FlagValue $cmd @('-m', '--message', '-am', '-ma')
        if ($msg) {
            # Extract subject line (first line)
            $subject = ($msg -split "`r?`n")[0].Trim()

            if ($subject -notmatch $conventionalPattern) {
                [Console]::Error.WriteLine("BLOCKED by commit-pr-guard hook:")
                [Console]::Error.WriteLine("  Commit message subject does not conform to Conventional Commits standard.")
                [Console]::Error.WriteLine("  Rejected subject: '$subject'")
                [Console]::Error.WriteLine("  Expected format: <type>(<optional-scope>): <description>")
                [Console]::Error.WriteLine("  Allowed types: feat, fix, docs, refactor, test, chore, perf, style, build, ci")
                [Console]::Error.WriteLine("  Example: fix(apu): defer frame counter reset by get-put phase (Closes #297)")
                exit 2
            }
        }
    }

    # 2. Check `gh pr create` / `gh pr edit`
    if ($cmd -match '\bgh\s+pr\s+(create|edit)\b') {
        $title = Extract-FlagValue $cmd @('-t', '--title')
        if ($title) {
            $subject = ($title -split "`r?`n")[0].Trim()

            if ($subject -notmatch $conventionalPattern) {
                [Console]::Error.WriteLine("BLOCKED by commit-pr-guard hook:")
                [Console]::Error.WriteLine("  PR title does not conform to Conventional Commits standard.")
                [Console]::Error.WriteLine("  Rejected title: '$subject'")
                [Console]::Error.WriteLine("  Expected format: <type>(<optional-scope>): <description>")
                [Console]::Error.WriteLine("  Allowed types: feat, fix, docs, refactor, test, chore, perf, style, build, ci")
                [Console]::Error.WriteLine("  Example: fix(apu): defer frame counter reset by get-put phase (Closes #297)")
                exit 2
            }
        }
    }

    exit 0
}
catch {
    # Fail open so unexpected parsing bugs don't wedge valid development
    [Console]::Error.WriteLine("commit-pr-guard hook error (allowing command): $_")
    exit 0
}
