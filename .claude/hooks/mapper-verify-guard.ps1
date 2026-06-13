<#
Stop hook: refuse to let the session end "done" when a mapper was changed but no boot
verification was actually run.

Why: delegated models routinely edit a gamepak/Mapper*.kt, claim success, and stop - while the
real game never booted (the strong Mesen2 gates skip when the oracle/ROM is absent, so nothing
goes red). This hook makes the claim un-makeable without evidence: if a mapper source file is
dirty in git and the transcript shows no bootcheck PASS/WARN (or a Mesen2 render MATCH), it blocks
the stop and tells the agent what to run.

Evidence accepted (any one):
  - "BOOTCHECK VERDICT: PASS" or "... WARN"  (./gradlew bootcheck -Prom=<rom>)
  - "render-output: MATCH" / "CHR banks: MATCH"  (a Mesen2 regression test ran)

Behaviour:
  - stop_hook_active (we already blocked once) -> exit 0  (never loop)
  - no mapper source dirty                     -> exit 0  (not a mapper session)
  - mapper dirty + evidence present            -> exit 0  (verified)
  - mapper dirty + no evidence                 -> exit 2  (BLOCK; stderr tells the agent what to run)
  - any error                                  -> exit 0  (fail open: a broken hook must never wedge a session)

Windows-first by design (this project's dev platform), mirroring worktree-guard.ps1.
ASCII-only on purpose: PowerShell 5.1 mis-parses non-ASCII (em-dash, smart quotes) in string literals.
#>
$ErrorActionPreference = 'Stop'

try {
    $payload = [Console]::In.ReadToEnd() | ConvertFrom-Json

    # Never re-block: if this hook already fired and the agent looped back, let it stop.
    if ($payload.stop_hook_active) { exit 0 }

    $projectDir = $env:CLAUDE_PROJECT_DIR
    if (-not $projectDir) { $projectDir = (Get-Location).Path }

    # Is any mapper SOURCE file dirty (modified, added, or untracked) in this worktree?
    $pathspec = 'src/main/kotlin/com/github/alondero/nestlin/gamepak/Mapper*.kt'
    $status = & git -C $projectDir status --porcelain -- $pathspec 2>$null
    if ($LASTEXITCODE -ne 0) { exit 0 }                      # not a git repo / git missing: fail open
    $dirty = @($status | Where-Object { $_ -and $_.Trim().Length -gt 0 })
    if ($dirty.Count -eq 0) { exit 0 }                       # no mapper changed this session

    # Look for verification evidence in the session transcript.
    $transcript = $payload.transcript_path
    $verified = $false
    if ($transcript -and (Test-Path $transcript)) {
        $text = Get-Content -Raw -LiteralPath $transcript
        # bootcheck PASS/WARN (a FAIL must NOT count), or a Mesen2 render/CHR MATCH from a regression
        # test. \b word boundaries stop "PASSABLE"/"WARNING"/"MISMATCH" from spuriously satisfying it.
        if ($text -match 'BOOTCHECK VERDICT:\s*(PASS|WARN)\b' -or
            $text -match 'render-output:\s*\bMATCH\b' -or
            $text -match 'CHR banks:\s*\bMATCH\b') {
            $verified = $true
        }
    }

    if ($verified) { exit 0 }

    $changed = ($dirty | ForEach-Object { $_.Substring([Math]::Min(3, $_.Length)) }) -join ', '
    $msg = "BLOCKED: you changed mapper source ($changed) but no boot verification ran this session. " +
        "A passing unit suite does not prove a real game boots - the Mesen2 gates skip when the " +
        "oracle/ROM is absent. Run the oracle-free smoke and cite the verdict before finishing:`n" +
        "  ./gradlew bootcheck -Prom=<path-to-a-rom-for-this-mapper>`n" +
        "Address a FAIL (did not load / blank screen) before claiming done. If you genuinely cannot " +
        "run it (no ROM available), say so explicitly to the user instead of stopping silently."
    [Console]::Error.WriteLine($msg)
    exit 2
}
catch {
    [Console]::Error.WriteLine("mapper-verify-guard hook error (allowing stop): $_")
    exit 0
}
