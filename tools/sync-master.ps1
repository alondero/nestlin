#requires -Version 5.0
<#
.SYNOPSIS
    One-command checkout of the configured branch (default: master), up to date with
    origin, with phantom-CRLF renormalize and a stale-worktree report.

.DESCRIPTION
    tools/sync-master.ps1 collapses a recurring "pull latest changes" workflow into
    one idempotent command. It exists because three of the last four agent sessions
    burned 5+ git commands each recovering from:
      1. CRLF phantom modifications (issue #112) that `git checkout --` cannot fix
      2. "fatal: 'master' is already checked out at .../worktrees/<name>" requiring
         inspection of stale worktrees
      3. Unscoped `**/` globs / searches timing out because of stale worktrees
         accumulating in .claude/worktrees/

    The script NEVER:
      * runs `git reset --hard`
      * runs `git stash` or `git stash drop`
      * runs `git worktree remove` (unless -PruneWorktrees is set, and only for
        clean, non-current worktrees)
      * commits or pushes anything
      * mutates global git config (only local, transient operations)

    It DOES:
      * `git fetch` from the configured remote
      * detect CRLF phantom diffs and run `git add --renormalize .` to stage them
      * `git checkout <branch>` (switching branches is non-destructive)
      * `git merge --ff-only <remote>/<branch>`
      * print a stale-worktree report with age, dirty state, and unpushed commits
        (always, even on success -- the report is the point)

.PARAMETER RepoPath
    Path to the git working tree to sync. Defaults to current directory.
    The path is treated as opaque; all git operations use `git -C`.

.PARAMETER Remote
    Remote to fetch from and fast-forward to. Default: origin.

.PARAMETER Branch
    Branch to sync. Default: master. The script switches to this branch if needed
    (switching is non-destructive, but will fail if the tree is dirty).

.PARAMETER PruneWorktrees
    Explicit opt-in switch. When set, the script will run `git worktree remove`
    on stale worktrees that are: (a) not the current one, (b) clean, (c) have no
    unpushed commits. WITHOUT this switch, the script only PRINTS the commands
    it would run.

.PARAMETER WorktreeBase
    Directory under which the script looks for stale worktrees. Default:
    .claude/worktrees (relative to RepoPath).

.PARAMETER DryRun
    If set, the script prints what it would do but performs no mutating operations
    (no fetch, no checkout, no merge, no renormalize, no prune).

.OUTPUTS
    PSCustomObject with properties:
        Status            One of: Synced, Renormalized, DirtyTree, WorktreeHoldsBranch,
                          FetchFailed, MergeFailed, CheckoutFailed, NothingToDo
        Message           Human-readable explanation
        ExitCode          0 on success/needs-no-action, 1 on needs-human-decision, 2 on git failure
        FilesNormalized   Array of file paths that were renormalized
        StaleWorktrees    Array of stale worktree info objects

.EXAMPLE
    PS> .	ools\sync-master.ps1
    # Sync current checkout to master, renormalize phantom CRLF, report stale worktrees

.EXAMPLE
    PS> .	ools\sync-master.ps1 -PruneWorktrees
    # Same as above, AND auto-remove clean, non-current stale worktrees

.EXAMPLE
    PS> .	ools\sync-master.ps1 -DryRun
    # Show what would happen, do nothing

.NOTES
    Issue: https://github.com/alondero/nestlin/issues/153
    Related: #112 (CRLF phantom), #62 (replay CLI, unrelated but same author pattern)
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Git helpers (all operate against an explicit -RepoPath; no global state)
# ---------------------------------------------------------------------------

function Get-GitRoot {
    param([string]$RepoPath = (Get-Location).Path)
    Push-Location $RepoPath -ErrorAction Stop
    try {
        $root = git rev-parse --show-toplevel 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $root) { return $null }
        return $root.Trim()
    } finally { Pop-Location }
}

function Get-GitCommonDir {
    # Returns the path to the main repo's .git dir.
    # In a worktree: this is the parent's .git (a `gitdir:` file points back).
    # In a non-worktree: this is the same as `git rev-parse --git-dir`.
    param([string]$RepoPath = (Get-Location).Path)
    Push-Location $RepoPath -ErrorAction Stop
    try {
        $commonDir = git rev-parse --git-common-dir 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $commonDir) { return $null }
        # git returns relative paths like ".git" -- resolve to absolute
        if (-not [System.IO.Path]::IsPathRooted($commonDir)) {
            $commonDir = Join-Path $RepoPath $commonDir
        }
        return (Resolve-Path $commonDir).Path
    } finally { Pop-Location }
}

function Test-IsInsideWorktree {
    param([string]$RepoPath = (Get-Location).Path)
    $commonDir = Get-GitCommonDir -RepoPath $RepoPath
    $gitDir = $null
    Push-Location $RepoPath -ErrorAction Stop
    try {
        $gitDir = git rev-parse --git-dir 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $gitDir) { return $false }
        if (-not [System.IO.Path]::IsPathRooted($gitDir)) {
            $gitDir = Join-Path $RepoPath $gitDir
        }
        $gitDir = (Resolve-Path $gitDir).Path
    } finally { Pop-Location }
    return ($gitDir -ne $commonDir)
}

function Get-CurrentBranch {
    param([string]$RepoPath = (Get-Location).Path)
    Push-Location $RepoPath -ErrorAction Stop
    try {
        $branch = git rev-parse --abbrev-ref HEAD 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        # "HEAD" indicates detached HEAD
        return $branch.Trim()
    } finally { Pop-Location }
}


# ---------------------------------------------------------------------------
# Phantom-CRLF detection (issue #112)
# ---------------------------------------------------------------------------

function Test-IsPhantomDirty {
    # Returns $true iff the working tree is dirty AND every change is line-ending only.
    #   * `git diff --ignore-cr-at-eol` is empty (working tree vs index, ignoring CRLF)
    #   * `git diff --cached --ignore-cr-at-eol` is empty (HEAD vs index, ignoring CRLF)
    #   * No untracked files (those would survive a merge but indicate the user is
    #     mid-work; treat as real dirty)
    param([string]$RepoPath = (Get-Location).Path)
    Push-Location $RepoPath -ErrorAction Stop
    try {
        $status = git status --porcelain 2>$null
        if ($LASTEXITCODE -ne 0) { throw "git status failed: $status" }
        if (-not $status) { return $false }
        # Untracked files are not phantom -- they have no index entry to renormalize
        $untracked = $status | Where-Object { $_ -match '^\?\? ' }
        if ($untracked) { return $false }
        # Staged or unstaged changes exist. Are they all line-ending?
        $diffUnstaged = git diff --ignore-cr-at-eol 2>$null
        if ($LASTEXITCODE -ne 0) { throw "git diff failed: $diffUnstaged" }
        if ($diffUnstaged) { return $false }
        $diffStaged = git diff --cached --ignore-cr-at-eol 2>$null
        if ($LASTEXITCODE -ne 0) { throw "git diff --cached failed: $diffStaged" }
        if ($diffStaged) { return $false }
        return $true
    } finally { Pop-Location }
}

function Get-PhantomFiles {
    # Returns array of objects: { Path, Status } for every phantom-dirty file.
    # `Status` is the first column of `git status --porcelain` (M/A/D/R/C etc).
    param([string]$RepoPath = (Get-Location).Path)
    Push-Location $RepoPath -ErrorAction Stop
    try {
        $status = git status --porcelain 2>$null
        if ($LASTEXITCODE -ne 0) { return @() }
        if (-not $status) { return @() }
        $result = @()
        foreach ($line in $status) {
            if ($line -match '^\?\? ') { continue }   # untracked
            $code = $line.Substring(0, 2).Trim()
            $path = $line.Substring(3).Trim()
            $result += [PSCustomObject]@{ Path = $path; Status = $code }
        }
        return $result
    } finally { Pop-Location }
}

function Invoke-Renormalize {
    param([string]$RepoPath = (Get-Location).Path)
    Push-Location $RepoPath -ErrorAction Stop
    try {
        $null = git add --renormalize . 2>$null
        if ($LASTEXITCODE -ne 0) { throw "git add --renormalize failed (exit $LASTEXITCODE)" }
        $files = git diff --cached --name-only 2>$null
        if ($LASTEXITCODE -ne 0) { return @() }
        return @($files | Where-Object { $_ })
    } finally { Pop-Location }
}

function Test-IsRealDirty {
    param([string]$RepoPath = (Get-Location).Path)
    Push-Location $RepoPath -ErrorAction Stop
    try {
        $status = git status --porcelain 2>$null
        if ($LASTEXITCODE -ne 0) { throw "git status failed: $status" }
        if (-not $status) { return $false }
        $untracked = $status | Where-Object { $_ -match '^\?\? ' }
        if ($untracked) { return $true }
        $diffUnstaged = git diff --ignore-cr-at-eol 2>$null
        if ($LASTEXITCODE -ne 0) { throw "git diff failed: $diffUnstaged" }
        if ($diffUnstaged) { return $true }
        $diffStaged = git diff --cached --ignore-cr-at-eol 2>$null
        if ($LASTEXITCODE -ne 0) { throw "git diff --cached failed: $diffStaged" }
        if ($diffStaged) { return $true }
        return $false
    } finally { Pop-Location }
}


# ---------------------------------------------------------------------------
# Worktree helpers
# ---------------------------------------------------------------------------

function ConvertFrom-WorktreePorcelainBlock {
    # Parses one `git worktree list --porcelain` block into a hashtable.
    param([string[]]$Lines)
    $info = [ordered]@{
        Path = $null
        HEAD = $null
        Branch = $null
        IsBare = $false
        IsDetached = $false
    }
    foreach ($line in $Lines) {
        if ($line -match '^worktree (.+)$') { $info.Path = $Matches[1].Trim() }
        elseif ($line -match '^HEAD ([0-9a-f]+)$') { $info.HEAD = $Matches[1].Trim() }
        elseif ($line -match '^branch ([^ ]+)$') { $info.Branch = $Matches[1].Trim() }
        elseif ($line -eq 'bare') { $info.IsBare = $true }
        elseif ($line -eq 'detached') { $info.IsDetached = $true }
    }
    return $info
}

function Get-AllWorktrees {
    # Returns an array of worktree info hashtables (one per `git worktree list` row).
    # `git worktree list` automatically walks the git common dir, so calling it from
    # any worktree shows ALL worktrees -- we just need to point git at a working tree
    # (NOT at the .git dir, which `git -C` would mis-interpret as a working tree).
    param([string]$RepoPath = (Get-Location).Path)
    if (-not (Test-Path $RepoPath)) { return @() }
    $listOutput = & git -C $RepoPath worktree list --porcelain 2>$null
    if ($LASTEXITCODE -ne 0) { return @() }
    $blocks = @()
    $current = @()
    foreach ($line in $listOutput) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            if ($current) { $blocks += ,$current; $current = @() }
        } else {
            $current += $line
        }
    }
    if ($current) { $blocks += ,$current }
    return @($blocks | ForEach-Object { ConvertFrom-WorktreePorcelainBlock -Lines $_ })
}

function Get-WorktreeHoldingBranch {
    # Returns the worktree info hashtable for the worktree currently holding $Branch
    # checked out, or $null if no worktree holds it.
    param(
        [string]$RepoPath = (Get-Location).Path,
        [string]$Branch
    )
    $all = Get-AllWorktrees -RepoPath $RepoPath
    foreach ($w in $all) {
        if ($w.Branch -eq "refs/heads/$Branch") { return $w }
    }
    return $null
}

function Get-StaleWorktrees {
    # Returns an array of objects describing worktrees under $Base (relative to
    # RepoPath) that are not the current one. Each object has:
    #   Path, Branch, Commit (short SHA), AgeDays, IsDirty, UnpushedCount, Prunable
    # Prunable = IsClean AND UnpushedCount -eq 0 (only when -PruneWorktrees will consider it)
    param(
        [string]$RepoPath = (Get-Location).Path,
        [string]$Base = '.claude/worktrees',
        [string]$ExcludePath = (Get-Location).Path
    )
    $all = Get-AllWorktrees -RepoPath $RepoPath
    $baseFull = Join-Path $RepoPath $Base
    $baseFull = [System.IO.Path]::GetFullPath($baseFull)
    $result = @()
    foreach ($w in $all) {
        if ($w.IsBare) { continue }
        if (-not $w.Path) { continue }
        $pathFull = [System.IO.Path]::GetFullPath($w.Path)
        if (-not $pathFull.StartsWith($baseFull, [System.StringComparison]::OrdinalIgnoreCase)) { continue }
        if ($pathFull -ieq $ExcludePath) { continue }
        $ageDays = $null
        if ($w.HEAD) {
            Push-Location $RepoPath -ErrorAction SilentlyContinue
            try {
                $ts = & git -C $RepoPath log -1 --format='%ct' $w.HEAD 2>$null
                if ($LASTEXITCODE -eq 0 -and $ts) {
                    $epoch = [int]$ts
                    $ageDays = [int][math]::Floor(((Get-Date).ToUniversalTime() - (Get-Date -Date '1970-01-01').ToUniversalTime().AddSeconds($epoch)).TotalDays)
                }
            } finally { Pop-Location }
        }
        $isDirty = $false
        if (Test-Path $pathFull) {
            Push-Location $pathFull -ErrorAction SilentlyContinue
            try {
                $s = git status --porcelain 2>$null
                if ($LASTEXITCODE -eq 0 -and $s) { $isDirty = $true }
            } finally { Pop-Location }
        }
        $unpushed = 0
        if ($w.Branch -and (Test-Path $pathFull)) {
            $local = & git -C $pathFull rev-parse $w.Branch 2>$null
            if ($LASTEXITCODE -eq 0 -and $local) {
                $remoteRef = "origin/$($w.Branch -replace '^refs/heads/','')"
                # `rev-parse --verify` understands the `origin/<branch>` shorthand
                # and resolves it to `refs/remotes/origin/<branch>`. (`show-ref --verify`
                # does NOT -- it only takes full ref paths.)
                #
                # CRITICAL: `git rev-parse --verify --quiet` still PRINTS the resolved
                # SHA on stdout when the ref exists -- --quiet only suppresses stderr
                # on failure. An uncaptured call's stdout leaks into the function's
                # return value, so we must pipe to Out-Null explicitly.
                $null = & git -C $pathFull rev-parse --verify --quiet $remoteRef 2>$null
                if ($LASTEXITCODE -eq 0) {
                    $count = & git -C $pathFull rev-list --count "$remoteRef..$($w.Branch)" 2>$null
                    if ($LASTEXITCODE -eq 0 -and $count) { $unpushed = [int]$count }
                } else {
                    # Remote ref doesn't exist (never pushed). Fall back to counting
                    # commits reachable from the local branch -- this is the
                    # "unpushed" count in the all-local sense.
                    $count = & git -C $pathFull rev-list --count $w.Branch 2>$null
                    if ($LASTEXITCODE -eq 0 -and $count) { $unpushed = [int]$count }
                }
            }
        }
        $shortSha = if ($w.HEAD) { $w.HEAD.Substring(0, [Math]::Min(7, $w.HEAD.Length)) } else { '' }
        $branchShort = if ($w.Branch) { $w.Branch -replace '^refs/heads/','' } else { '(detached)' }
        $result += [PSCustomObject]@{
            Path = $w.Path
            Branch = $branchShort
            Commit = $shortSha
            AgeDays = $ageDays
            IsDirty = $isDirty
            UnpushedCount = $unpushed
            Prunable = (-not $isDirty) -and ($unpushed -eq 0) -and (-not $w.IsBare)
        }
    }
    return $result
}

function Format-StaleWorktreeReport {
    param($Worktrees)
    if (-not $Worktrees -or $Worktrees.Count -eq 0) {
        return "Stale worktrees: none (clean)"
    }
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("Stale worktrees under configured base:")
    $w = "{0,-50} {1,-30} {2,8} {3,-6} {4,8}" -f 'PATH','BRANCH','AGE(d)','DIRTY','UNPUSHED'
    [void]$sb.AppendLine("  $w")
    [void]$sb.AppendLine("  " + ('-' * 110))
    foreach ($t in ($Worktrees | Sort-Object AgeDays -Descending)) {
        $line = "{0,-50} {1,-30} {2,8} {3,-6} {4,8}" -f `
            $t.Path, $t.Branch, ($t.AgeDays -as [string]), $t.IsDirty, $t.UnpushedCount
        [void]$sb.AppendLine("  $line")
    }
    $prunable = @($Worktrees | Where-Object { $_.Prunable }).Count
    if ($prunable -gt 0) {
        [void]$sb.AppendLine("")
        [void]$sb.AppendLine("$prunable of these are prunable (clean, no unpushed). Re-run with -PruneWorktrees to remove.")
    }
    return $sb.ToString().TrimEnd()
}


# ---------------------------------------------------------------------------
# Orchestrator
# ---------------------------------------------------------------------------

function Sync-MasterToOrigin {
    [CmdletBinding()]
    param(
        [string]$RepoPath = (Get-Location).Path,
        [string]$Remote = 'origin',
        [string]$Branch = 'master',
        [string]$WorktreeBase = '.claude/worktrees',
        [switch]$PruneWorktrees,
        [switch]$DryRun,
        [switch]$Quiet
    )

    $result = [PSCustomObject]@{
        Status = ''
        Message = ''
        ExitCode = 0
        FilesNormalized = @()
        StaleWorktrees = @()
    }

    function Write-Log {
        param([string]$Msg)
        if (-not $Quiet) { Write-Host $Msg }
    }

    # 0. Validate we're in a git repo
    $root = Get-GitRoot -RepoPath $RepoPath
    if (-not $root) {
        $result.Status = 'NotARepo'
        $result.Message = "Not a git repository: $RepoPath"
        $result.ExitCode = 2
        return $result
    }
    $RepoPath = $root

    # 1. Phantom-CRLF detection and renormalize
    $isPhantom = Test-IsPhantomDirty -RepoPath $RepoPath
    if ($isPhantom) {
        $phantomFiles = Get-PhantomFiles -RepoPath $RepoPath
        $paths = @($phantomFiles | ForEach-Object { $_.Path })
        if ($DryRun) {
            Write-Log "[dry-run] Would renormalize: $($paths -join ', ')"
        } else {
            $normalized = Invoke-Renormalize -RepoPath $RepoPath
            $result.FilesNormalized = $normalized
            Write-Log "Renormalized (staged): $($normalized -join ', ')"
        }
        $result.Status = 'Renormalized'
        $result.Message = "Phantom CRLF diffs staged via `git add --renormalize .` (issue #112). Commit them and re-run to fast-forward master."
        $result.ExitCode = 1
        $result.StaleWorktrees = Get-StaleWorktrees -RepoPath $RepoPath -Base $WorktreeBase -ExcludePath $RepoPath
        if (-not $Quiet) { Write-Log (Format-StaleWorktreeReport $result.StaleWorktrees) }
        return $result
    }

    # 2. Real-dirty check
    if (Test-IsRealDirty -RepoPath $RepoPath) {
        $result.Status = 'DirtyTree'
        $result.Message = "Working tree has uncommitted changes that are not phantom CRLF. Commit or stash them, then re-run. (No destructive action taken.)"
        $result.ExitCode = 1
        $result.StaleWorktrees = Get-StaleWorktrees -RepoPath $RepoPath -Base $WorktreeBase -ExcludePath $RepoPath
        if (-not $Quiet) { Write-Log (Format-StaleWorktreeReport $result.StaleWorktrees) }
        return $result
    }

    # 3. Branch state
    $currentBranch = Get-CurrentBranch -RepoPath $RepoPath
    $onTargetBranch = ($currentBranch -eq $Branch)

    # 3a. Detached HEAD: `git rev-parse --abbrev-ref HEAD` returns the literal
    # string "HEAD" when detached. Don't pretend the user is on a different
    # branch and try to `git checkout $Branch` -- that fails with a confusing
    # message. Tell them explicitly to switch to a branch first.
    if ($currentBranch -eq 'HEAD') {
        $result.Status = 'DetachedHead'
        $result.Message = "Repository is in detached HEAD state at $RepoPath. `git checkout $Branch` (or any named branch) first, then re-run."
        $result.ExitCode = 1
        $result.StaleWorktrees = Get-StaleWorktrees -RepoPath $RepoPath -Base $WorktreeBase -ExcludePath $RepoPath
        if (-not $Quiet) { Write-Log (Format-StaleWorktreeReport $result.StaleWorktrees) }
        return $result
    }

    # 4. Worktree-held branch detection
    if (-not $onTargetBranch) {
        $holding = Get-WorktreeHoldingBranch -RepoPath $RepoPath -Branch $Branch
        if ($holding) {
            $result.Status = 'WorktreeHoldsBranch'
            $result.Message = "Cannot checkout '$Branch' -- it is currently checked out at: $($holding.Path). Use that worktree, or remove it (`git worktree remove` requires -PruneWorktrees)."
            $result.ExitCode = 1
            $all = Get-StaleWorktrees -RepoPath $RepoPath -Base $WorktreeBase -ExcludePath $RepoPath
            $result.StaleWorktrees = $all
            if (-not $Quiet) { Write-Log (Format-StaleWorktreeReport $all) }
            return $result
        }
    }

    # 5. Switch to target branch
    if (-not $onTargetBranch) {
        if ($DryRun) {
            Write-Log "[dry-run] Would git checkout $Branch"
        } else {
            Push-Location $RepoPath -ErrorAction Stop
            try {
                $co = git checkout $Branch 2>&1
                if ($LASTEXITCODE -ne 0) {
                    $result.Status = 'CheckoutFailed'
                    $result.Message = "git checkout $Branch failed: $co"
                    $result.ExitCode = 2
                    return $result
                }
            } finally { Pop-Location }
        }
    }

    # 6. Fetch
    if ($DryRun) {
        Write-Log "[dry-run] Would git fetch $Remote"
    } else {
        Push-Location $RepoPath -ErrorAction Stop
        try {
            $fetch = git fetch $Remote 2>&1
            if ($LASTEXITCODE -ne 0) {
                $result.Status = 'FetchFailed'
                $result.Message = "git fetch $Remote failed: $fetch"
                $result.ExitCode = 2
                return $result
            }
        } finally { Pop-Location }
    }

    # 7. Fast-forward merge
    if ($DryRun) {
        Write-Log "[dry-run] Would git merge --ff-only $Remote/$Branch"
    } else {
        Push-Location $RepoPath -ErrorAction Stop
        try {
            $merge = git merge --ff-only "$Remote/$Branch" 2>&1
            if ($LASTEXITCODE -ne 0) {
                $result.Status = 'MergeFailed'
                $result.Message = "git merge --ff-only $Remote/$Branch failed: $merge (likely local master has diverged; non-ff changes require a manual merge)."
                $result.ExitCode = 2
                return $result
            }
        } finally { Pop-Location }
    }

    # 8. Stale-worktree report (always)
    $stale = Get-StaleWorktrees -RepoPath $RepoPath -Base $WorktreeBase -ExcludePath $RepoPath
    $result.StaleWorktrees = $stale
    if (-not $Quiet) { Write-Log (Format-StaleWorktreeReport $stale) }

    # 9. Optional prune
    if ($PruneWorktrees -and $stale) {
        $prunable = @($stale | Where-Object { $_.Prunable })
        foreach ($t in $prunable) {
            if ($DryRun) {
                Write-Log "[dry-run] Would remove: $($t.Path)"
            } else {
                Push-Location (Get-GitCommonDir -RepoPath $RepoPath) -ErrorAction SilentlyContinue
                try {
                    $rm = git worktree remove --force $t.Path 2>&1
                    if ($LASTEXITCODE -ne 0) {
                        Write-Log "Could not remove $($t.Path): $rm"
                    } else {
                        Write-Log "Pruned: $($t.Path)"
                    }
                } finally { Pop-Location }
            }
        }
    }

    $result.Status = 'Synced'
    $result.Message = "On $Branch, up to date with $Remote/$Branch."
    $result.ExitCode = 0
    return $result
}

# ---------------------------------------------------------------------------
# Entry point (only runs when invoked as a script, NOT when dot-sourced for tests)
# ---------------------------------------------------------------------------

if ($MyInvocation.MyCommand.Path -ne $null -and $MyInvocation.InvocationName -ne '.') {
    $params = @{}
    if ($args.Count -gt 0) {
        for ($i = 0; $i -lt $args.Count; $i++) {
            $a = $args[$i]
            if ($a -like '-*') {
                $key = $a.TrimStart('-')
                $val = $true
                if ($i + 1 -lt $args.Count -and $args[$i+1] -notlike '-*') {
                    $val = $args[$i+1]
                    $i++
                }
                $params[$key] = $val
            }
        }
    }
    $result = Sync-MasterToOrigin @params
    if ($result.Message) { Write-Host $result.Message }
    exit $result.ExitCode
}
