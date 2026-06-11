<#
PreToolUse hook: block Write/Edit calls that escape a Claude Code worktree into the
parent repository.

Why: sessions running inside .claude/worktrees/<name>/ have twice written files into
the parent repo (X:\src\nestlin\src\...) by using the parent's absolute path instead
of the worktree's. The global instructions forbid it, but instructions are advisory —
this hook makes it mechanical.

Behavior:
  - Not in a worktree session  -> exit 0 (allow, instantly)
  - Target inside the worktree -> exit 0 (allow)
  - Target inside the PARENT repo (the part of the path before \.claude\worktrees\)
                               -> exit 2 (BLOCK; stderr tells the agent the corrected path)
  - Target elsewhere (memory dir, temp, other drives) -> exit 0 (not this hook's business)

Windows-first by design (this project's dev platform). On hosts without powershell the
hook fails non-blocking and Claude Code proceeds.
#>
$ErrorActionPreference = 'Stop'

try {
    $payload = [Console]::In.ReadToEnd() | ConvertFrom-Json

    $projectDir = $env:CLAUDE_PROJECT_DIR
    if (-not $projectDir) { $projectDir = (Get-Location).Path }

    # Only act when the session itself lives inside a worktree.
    $marker = '\.claude\worktrees\'
    $idx = $projectDir.ToLowerInvariant().IndexOf($marker.ToLowerInvariant())
    if ($idx -lt 0) { exit 0 }
    $parentRepo = $projectDir.Substring(0, $idx)

    $target = $payload.tool_input.file_path
    if (-not $target) { $target = $payload.tool_input.notebook_path }
    if (-not $target) { exit 0 }

    if (-not [System.IO.Path]::IsPathRooted($target)) {
        $target = Join-Path $projectDir $target
    }
    $target = [System.IO.Path]::GetFullPath($target)

    $t = $target.ToLowerInvariant()
    $wt = ($projectDir.TrimEnd('\') + '\').ToLowerInvariant()
    $pr = ($parentRepo.TrimEnd('\') + '\').ToLowerInvariant()

    if ($t.StartsWith($wt)) { exit 0 }          # inside the worktree: fine
    if ($t.StartsWith($pr)) {
        $relative = $target.Substring($parentRepo.TrimEnd('\').Length).TrimStart('\')
        [Console]::Error.WriteLine(
            "BLOCKED: this session runs in the worktree '$projectDir' but the write targets the PARENT repo: $target. " +
            "Use the worktree path instead: $(Join-Path $projectDir $relative)")
        exit 2
    }
    exit 0
}
catch {
    # A broken hook must never block legitimate work — fail open, but say why.
    [Console]::Error.WriteLine("worktree-guard hook error (allowing call): $_")
    exit 0
}
