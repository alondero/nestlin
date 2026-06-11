# Pester 3.4 tests for tools/sync-master.ps1
#
# Run with:
#   powershell.exe -NoProfile -Command "Invoke-Pester tools	ests\sync-master.Tests.ps1"
#
# Or via the helper:
#   tools/tests/run-tests.sh
#
# Coverage:
#   * Test-IsPhantomDirty  : clean / phantom / real / staged-phantom
#   * Get-PhantomFiles     : lists the exact files
#   * Invoke-Renormalize   : makes the tree clean (after the renormalize is committed)
#   * Test-IsRealDirty     : catches content changes
#   * Get-WorktreeHoldingBranch : detects "fatal: already checked out at ..."
#   * Get-StaleWorktrees   : lists, ages, dirty flags
#   * Sync-MasterToOrigin  : end-to-end orchestrator (phantom, real-dirty, ff-merge, idempotency)
#
# All tests use isolated temp git repos so they cannot corrupt the dev checkout.

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$scriptPath = Join-Path $here '..\sync-master.ps1'

# Dot-source the script under test. Entry-point guard prevents orchestrator from running.
. $scriptPath


# --- Fixture helpers -------------------------------------------------------

function New-TempRepo {
    $path = Join-Path ([System.IO.Path]::GetTempPath()) ("sync-master-test-" + [System.Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $path | Out-Null
    Push-Location $path
    try {
        git init -q -b master 2>&1 | Out-Null
        git config user.email 'sync-master-test@example.com' | Out-Null
        git config user.name 'sync-master-test' | Out-Null
        # CRITICAL: turn off autocrlf inside the test repo. We control EOLs with explicit bytes
        # and a hand-built .gitattributes so phantom diffs are reproducible.
        git config core.autocrlf false | Out-Null
        # Required for `git worktree add` to succeed (needs at least one commit to fork from).
        git commit --allow-empty -q -m 'initial' | Out-Null
    } finally {
        Pop-Location
    }
    return $path
}

function New-TempRepoWithRemote {
    param([string]$RemoteName = 'origin')
    $path = New-TempRepo
    $remotePath = Join-Path ([System.IO.Path]::GetTempPath()) ("sync-master-remote-" + [System.Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $remotePath | Out-Null
    Push-Location $remotePath
    try {
        git init -q --bare 2>&1 | Out-Null
    } finally {
        Pop-Location
    }
    Push-Location $path
    try {
        git remote add $RemoteName $remotePath | Out-Null
        git push -q $RemoteName master | Out-Null
    } finally {
        Pop-Location
    }
    return $path
}

function Remove-TempRepo {
    param([string]$Path)
    if (Test-Path $Path) { Remove-Item -Recurse -Force $Path }
}

# Write $Content to $RepoPath/$RelPath with explicit LF or CRLF line endings.
# NOTE: parameter name `LineEnding` (not `Eol`) to dodge a PowerShell 5.1 + ValidateSet
# quirk where `Set-StrictMode -Version Latest` triggers
#   "The variable cannot be validated because the value is not a valid value for the Eol variable"
# at runtime even when the value IS in the ValidateSet. Renaming sidesteps it.
# (The script under test, sync-master.ps1, sets StrictMode when it is dot-sourced above.)
function Write-FileWithEol {
    param(
        [string]$RepoPath,
        [string]$RelPath,
        [string]$Content,
        [ValidateSet('LF','CRLF')][string]$LineEnding = 'LF'
    )
    $eol = if ($LineEnding -eq 'LF') { "`n" } else { "`r`n" }
    $normalised = ($Content -replace "`r`n", "`n") -replace "`n", $eol
    $full = Join-Path $RepoPath $RelPath
    $dir = Split-Path -Parent $full
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    # WriteAllBytes (not WriteAllText) so no UTF-8 BOM is prepended -- a BOM would
    # change the byte stream and break the phantom-diff reproduction.
    [System.IO.File]::WriteAllBytes($full, [System.Text.Encoding]::ASCII.GetBytes($normalised))
}

# Build the canonical phantom-diff scenario reliably:
#   1. Write sample.txt as raw CRLF bytes (no BOM, explicit)
#   2. Commit it (autocrlf=false in this repo, raw bytes go to the index)
#   3. Add .gitattributes that says eol=crlf -- NOW the smudge filter expects CRLF
#   4. Working tree still has CRLF, index still has CRLF, but git's stat cache sees
#      the smudge filter would re-emit LF because the index blob was committed
#      with no .gitattributes and is now "stale" relative to the new rule.
#      Result: `git status` says modified; `git diff --ignore-cr-at-eol` is empty.
#   5. `git update-index --refresh` forces the stat cache to recompute (the test
#      fixture was flaky without it on Windows NTFS due to mtime resolution issues)
function Set-PhantomScenario {
    param([string]$RepoPath)
    Push-Location $RepoPath
    try {
        # Write raw CRLF bytes (no BOM) so git's hash is deterministic
        $crlf = "`r`n"
        $content = "line1$crlf" + "line2$crlf"
        $path = Join-Path $RepoPath 'sample.txt'
        [System.IO.File]::WriteAllBytes($path, [System.Text.Encoding]::ASCII.GetBytes($content))

        git add sample.txt | Out-Null
        git commit -q -m 'init' | Out-Null

        # Add .gitattributes in a SECOND commit so the file is committed under
        # the OLD normalization, then the .gitattributes makes the new rule.
        # Write via WriteAllBytes (ASCII) so no UTF-8 BOM sneaks in -- a BOM
        # in the .gitattributes file would change which byte-sequence matches
        # the rule and break the phantom-diff reproduction.
        $attr = '*.txt text eol=crlf'
        [System.IO.File]::WriteAllBytes(
            (Join-Path $RepoPath '.gitattributes'),
            [System.Text.Encoding]::ASCII.GetBytes($attr))
        git add .gitattributes | Out-Null
        git commit -q -m 'pin eol' | Out-Null

        # Force the stat cache to recompute. Without this, the scenario is flaky
        # because git caches the on-disk mtime and re-checks lazily.
        git update-index --refresh 2>&1 | Out-Null

        $status = git status --porcelain
        $diff = git diff --ignore-cr-at-eol
        $diffCached = git diff --cached --ignore-cr-at-eol
        if (-not $status -or $diff -or $diffCached) {
            throw "Phantom scenario setup failed. status='$status' diff='$diff' diffCached='$diffCached'"
        }
    } finally {
        Pop-Location
    }
}

function Set-RealDirtyScenario {
    param([string]$RepoPath)
    Push-Location $RepoPath
    try {
        Write-FileWithEol -RepoPath $RepoPath -RelPath 'real.txt' -Content "before`n" -LineEnding 'LF' | Out-Null
        git add real.txt | Out-Null
        git commit -q -m 'init' | Out-Null
        Write-FileWithEol -RepoPath $RepoPath -RelPath 'real.txt' -Content "after`n" -LineEnding 'LF' | Out-Null
    } finally {
        Pop-Location
    }
}


Describe 'Test-IsPhantomDirty' {
    It 'returns $false on a clean repo' {
        $repo = New-TempRepo
        try {
            Test-IsPhantomDirty -RepoPath $repo | Should Be $false
        } finally { Remove-TempRepo $repo }
    }

    It 'returns $true on a phantom-CRLF dirty repo' {
        $repo = New-TempRepo
        try {
            Set-PhantomScenario -RepoPath $repo
            Test-IsPhantomDirty -RepoPath $repo | Should Be $true
        } finally { Remove-TempRepo $repo }
    }

    It 'returns $false on a real (non-line-ending) dirty repo' {
        $repo = New-TempRepo
        try {
            Set-RealDirtyScenario -RepoPath $repo
            Test-IsPhantomDirty -RepoPath $repo | Should Be $false
        } finally { Remove-TempRepo $repo }
    }

    It 'returns $false when phantom is mixed with an untracked file' {
        $repo = New-TempRepo
        try {
            Set-PhantomScenario -RepoPath $repo
            # Add an untracked file -- that is real work-in-progress, not phantom
            Set-Content -Path (Join-Path $repo 'new.txt') -Value 'untracked' -NoNewline
            Test-IsPhantomDirty -RepoPath $repo | Should Be $false
        } finally { Remove-TempRepo $repo }
    }
}

Describe 'Get-PhantomFiles' {
    It 'returns an empty array on a clean repo' {
        $repo = New-TempRepo
        try {
            @(Get-PhantomFiles -RepoPath $repo).Count | Should Be 0
        } finally { Remove-TempRepo $repo }
    }

    It 'returns the phantom-dirty file paths' {
        $repo = New-TempRepo
        try {
            Set-PhantomScenario -RepoPath $repo
            $files = @(Get-PhantomFiles -RepoPath $repo)
            $files.Count | Should Be 1
            $files[0].Path | Should Be 'sample.txt'
            $files[0].Status | Should Be 'M'
        } finally { Remove-TempRepo $repo }
    }
}

Describe 'Invoke-Renormalize' {
    It 'stages the phantom diff so a follow-up commit clears it' {
        $repo = New-TempRepo
        try {
            Set-PhantomScenario -RepoPath $repo
            $staged = Invoke-Renormalize -RepoPath $repo
            # Pester 3.4's `Should Contain` is a FILE-content assertion, not an
            # array-membership check. Use PowerShell's native `-contains`.
            ($staged -contains 'sample.txt') | Should Be $true
            # After commit, phantom is gone
            Push-Location $repo
            try {
                git commit -q -m 'normalize' | Out-Null
            } finally { Pop-Location }
            Test-IsPhantomDirty -RepoPath $repo | Should Be $false
        } finally { Remove-TempRepo $repo }
    }
}

Describe 'Test-IsRealDirty' {
    It 'returns $false on a clean repo' {
        $repo = New-TempRepo
        try {
            Test-IsRealDirty -RepoPath $repo | Should Be $false
        } finally { Remove-TempRepo $repo }
    }

    It 'returns $false on a phantom-dirty repo' {
        $repo = New-TempRepo
        try {
            Set-PhantomScenario -RepoPath $repo
            Test-IsRealDirty -RepoPath $repo | Should Be $false
        } finally { Remove-TempRepo $repo }
    }

    It 'returns $true on a real-dirty repo' {
        $repo = New-TempRepo
        try {
            Set-RealDirtyScenario -RepoPath $repo
            Test-IsRealDirty -RepoPath $repo | Should Be $true
        } finally { Remove-TempRepo $repo }
    }
}

Describe 'Get-WorktreeHoldingBranch' {
    It 'returns the worktree currently holding the branch' {
        $repo = New-TempRepoWithRemote
        $wtRoot = Join-Path $repo '.claude/worktrees'
        $wtPath = Join-Path $wtRoot 'feature-here'
        try {
            New-Item -ItemType Directory -Path $wtRoot | Out-Null
            Push-Location $repo
            try {
                git worktree add -b feature/here $wtPath master 2>&1 | Out-Null
            } finally { Pop-Location }
            $holding = Get-WorktreeHoldingBranch -RepoPath $repo -Branch 'feature/here'
            $holding | Should Not Be $null
            $normExpected = ($wtPath -replace '\\','/')
            $normActual = ($holding.Path -replace '\\','/')
            $normActual | Should Be $normExpected
        } finally { Remove-TempRepo $repo }
    }

    It 'returns $null when no worktree holds the branch' {
        $repo = New-TempRepoWithRemote
        try {
            $holding = Get-WorktreeHoldingBranch -RepoPath $repo -Branch 'nonexistent'
            $holding | Should Be $null
        } finally { Remove-TempRepo $repo }
    }
}

Describe 'Get-StaleWorktrees' {
    It 'returns an empty array when no worktrees exist under the base' {
        $repo = New-TempRepoWithRemote
        try {
            $stale = @(Get-StaleWorktrees -RepoPath $repo -Base '.claude/worktrees' -ExcludePath $repo)
            $stale.Count | Should Be 0
        } finally { Remove-TempRepo $repo }
    }

    It 'lists a clean prunable worktree under the base with correct fields' {
        $repo = New-TempRepoWithRemote
        $wtRoot = Join-Path $repo '.claude/worktrees'
        $wtPath = Join-Path $wtRoot 'listme'
        try {
            New-Item -ItemType Directory -Path $wtRoot | Out-Null
            Push-Location $repo
            try {
                git worktree add -b feature/listme $wtPath master 2>&1 | Out-Null
            } finally { Pop-Location }
            # Push the feature branch from the WORKTREE -- the branch is
            # local to the worktree, not the main repo. Once pushed,
            # origin/feature/listme matches feature/listme, so UnpushedCount = 0.
            # (Without this push, Get-StaleWorktrees falls back to counting
            # ALL commits reachable from the local branch, which is 1
            # for a brand-new branch -- giving the wrong UnpushedCount.)
            Push-Location $wtPath
            try {
                git push -q origin feature/listme 2>&1 | Out-Null
            } finally { Pop-Location }
            $stale = @(Get-StaleWorktrees -RepoPath $repo -Base '.claude/worktrees' -ExcludePath $repo)
            $stale.Count | Should Be 1
            $stale[0].Branch | Should Be 'feature/listme'
            $stale[0].IsDirty | Should Be $false
            $stale[0].UnpushedCount | Should Be 0
            $stale[0].Prunable | Should Be $true
        } finally {
            # Clean up worktree from a known-good cwd (the main repo)
            Push-Location $repo
            try { & git -C $repo worktree remove --force $wtPath 2>&1 | Out-Null } catch {}
            Pop-Location
            Remove-TempRepo $repo
        }
    }
}

Describe 'Sync-MasterToOrigin' {
    It 'returns Renormalized + ExitCode 1 when phantom CRLF is detected' {
        $repo = New-TempRepoWithRemote
        try {
            Set-PhantomScenario -RepoPath $repo
            $out = Sync-MasterToOrigin -RepoPath $repo -WorktreeBase '.claude/worktrees' -Quiet
            $out.Status | Should Be 'Renormalized'
            $out.ExitCode | Should Be 1
            ($out.FilesNormalized -contains 'sample.txt') | Should Be $true
        } finally { Remove-TempRepo $repo }
    }

    It 'returns DirtyTree + ExitCode 1 when there are real (non-CRLF) changes' {
        $repo = New-TempRepoWithRemote
        try {
            Set-RealDirtyScenario -RepoPath $repo
            $out = Sync-MasterToOrigin -RepoPath $repo -WorktreeBase '.claude/worktrees' -Quiet
            $out.Status | Should Be 'DirtyTree'
            $out.ExitCode | Should Be 1
        } finally { Remove-TempRepo $repo }
    }

    It 'fast-forwards master to origin and returns Synced + ExitCode 0' {
        $repo = New-TempRepoWithRemote
        try {
            Push-Location $repo
            try {
                # Advance master on the remote by making a new commit and pushing
                Write-FileWithEol -RepoPath $repo -RelPath 'README.md' -Content "hello`n" -LineEnding 'LF' | Out-Null
                git add README.md | Out-Null
                git commit -q -m 'remote commit' | Out-Null
                git push -q origin master | Out-Null
            } finally { Pop-Location }
            $out = Sync-MasterToOrigin -RepoPath $repo -WorktreeBase '.claude/worktrees' -Quiet
            $out.Status | Should Be 'Synced'
            $out.ExitCode | Should Be 0
        } finally { Remove-TempRepo $repo }
    }

    It 'is idempotent -- a second run after Synced is still Synced' {
        $repo = New-TempRepoWithRemote
        try {
            Push-Location $repo
            try {
                Write-FileWithEol -RepoPath $repo -RelPath 'README.md' -Content "hello`n" -LineEnding 'LF' | Out-Null
                git add README.md | Out-Null
                git commit -q -m 'remote commit' | Out-Null
                git push -q origin master | Out-Null
            } finally { Pop-Location }
            $first = Sync-MasterToOrigin -RepoPath $repo -WorktreeBase '.claude/worktrees' -Quiet
            $second = Sync-MasterToOrigin -RepoPath $repo -WorktreeBase '.claude/worktrees' -Quiet
            $first.Status | Should Be 'Synced'
            $second.Status | Should Be 'Synced'
            $first.ExitCode | Should Be 0
            $second.ExitCode | Should Be 0
        } finally { Remove-TempRepo $repo }
    }

    It 'returns DetachedHead + ExitCode 1 when HEAD is detached' {
        # Regression: when the user is in detached-HEAD state, `git rev-parse
        # --abbrev-ref HEAD` returns the literal "HEAD" -- which doesn't match
        # $Branch. Before the fix, the orchestrator would attempt `git checkout
        # master` and fail with a confusing "CheckoutFailed" error.
        $repo = New-TempRepoWithRemote
        try {
            Push-Location $repo
            try {
                # Detach HEAD from any branch. The script under test sets
                # `$ErrorActionPreference = 'Stop'` (dot-sourced above), so any
                # native command that writes to stderr (even captured to $null)
                # throws a RemoteException. Temporarily set Continue for the
                # setup so the informational "HEAD is now at ..." message
                # doesn't fail the test, then restore.
                $prevPref = $ErrorActionPreference
                $ErrorActionPreference = 'Continue'
                try {
                    $null = & git checkout --detach HEAD 2>&1
                } catch { }
                $ErrorActionPreference = $prevPref
                # Assert that we actually got into the detached state,
                # otherwise the regression test would pass on a setup bug.
                $head = & git rev-parse --abbrev-ref HEAD 2>$null
                if ($head -ne 'HEAD') { throw "Test setup failed: HEAD did not detach (got '$head')" }
            } finally { Pop-Location }
            $out = Sync-MasterToOrigin -RepoPath $repo -WorktreeBase '.claude/worktrees' -Quiet
            $out.Status | Should Be 'DetachedHead'
            $out.ExitCode | Should Be 1
        } finally { Remove-TempRepo $repo }
    }
}
