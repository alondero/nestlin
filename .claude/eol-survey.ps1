$patterns = @('*.kt','*.kts','*.md','*.py','*.lua','*.json','*.yml')
$exclude = @('\.git\','\.gradle\','build\','node_modules\','\.worktree','\.claude\')

$root = (Get-Location).Path

# Collect unique file paths across all patterns
$fileList = New-Object System.Collections.Generic.HashSet[string]
foreach ($pat in $patterns) {
    Get-ChildItem -Recurse -File -Filter $pat -ErrorAction SilentlyContinue | ForEach-Object {
        $rel = $_.FullName.Substring($root.Length)
        $skip = $false
        foreach ($ex in $exclude) {
            if ($rel -match [regex]::Escape($ex)) { $skip = $true; break }
        }
        if (-not $skip) { [void]$fileList.Add($_.FullName) }
    }
}

$results = @()
foreach ($path in $fileList) {
    $rel = $path.Substring($root.Length + 1)
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $crlf = 0; $lf = 0
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        if ($bytes[$i] -eq 0x0A) {
            if ($i -gt 0 -and $bytes[$i-1] -eq 0x0D) { $crlf++ } else { $lf++ }
        }
    }
    if ($crlf -gt 0 -and $lf -gt 0) { $kind = 'MIXED' }
    elseif ($crlf -gt 0) { $kind = 'CRLF' }
    elseif ($lf -gt 0) { $kind = 'LF' }
    else { $kind = 'NONE' }
    $results += [PSCustomObject]@{ Kind = $kind; CRLF = $crlf; LF = $lf; Path = $rel }
}

Write-Host '--- summary ---'
$summary = $results | Group-Object Kind
foreach ($g in $summary) {
    '{0,-7}  {1}' -f $g.Name, $g.Count
}
if (-not $summary) { '  (no files found)' }

Write-Host ''
Write-Host '--- per-file (sorted) ---'
foreach ($r in ($results | Sort-Object Kind, Path)) {
    '{0,-7}  CRLF={1,-5} LF={2,-5}  {3}' -f $r.Kind, $r.CRLF, $r.LF, $r.Path
}
