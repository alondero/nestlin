<#
.SYNOPSIS
Run a single JUnit test class (or method) and print ONLY its output — no Gradle noise.

.DESCRIPTION
Exists because the obvious ways to see println output from a diagnostic test all fail:
  - `gradlew test --tests X --info` emits null bytes that break text pipelines
  - Gradle's UP-TO-DATE check silently skips a cached test (no output, looks green)
  - the real output lands in build/test-results/<task>/TEST-*.xml, wrapped in XML escaping

This script runs the test with --rerun-tasks (defeats the cache), then extracts the
<system-out>/<system-err> and failure details straight from the result XML.

.EXAMPLE
  .\tools\run-diag.ps1 -TestClass GoldenLogTest
  .\tools\run-diag.ps1 -TestClass com.github.alondero.nestlin.compare.Mapper10RegressionTest -Mesen
  .\tools\run-diag.ps1 -TestClass Mapper64Test -Method "irq fires once per frame"
#>
param(
    [Parameter(Mandatory = $true)][string]$TestClass,
    [string]$Method,
    [switch]$Mesen   # run via the testMesenComparison task (Mesen2-dependent tests are excluded from plain `test`)
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$task = if ($Mesen) { 'testMesenComparison' } else { 'test' }
$filter = if ($Method) { "$TestClass.$Method" } else { "*$TestClass*" }

Push-Location $repoRoot
try {
    # cmd /c so stderr merges without PowerShell 5.1 wrapping it in ErrorRecords.
    $gradleOut = & cmd /c "gradlew.bat $task --tests `"$filter`" --rerun-tasks -q 2>&1"
    $gradleExit = $LASTEXITCODE

    $resultsDir = Join-Path $repoRoot "build\test-results\$task"
    $xmlFiles = @()
    if (Test-Path $resultsDir) {
        $xmlFiles = Get-ChildItem $resultsDir -Filter 'TEST-*.xml' |
            Where-Object { $_.BaseName -like "*$TestClass*" }
    }

    if ($xmlFiles.Count -eq 0) {
        # No results = the test never ran (compile error, bad filter, wrong task).
        # This is the one case where Gradle's own output is the diagnostic.
        Write-Output "No test results found for '$TestClass' under $resultsDir. Gradle output:"
        # Strip null bytes — Gradle's rich console injects them and they break text tools.
        Write-Output (($gradleOut | Out-String) -replace "`0", '')
        exit ($(if ($gradleExit -ne 0) { $gradleExit } else { 1 }))
    }

    foreach ($file in $xmlFiles) {
        [xml]$xml = (Get-Content $file.FullName -Raw) -replace "`0", ''
        $suite = $xml.testsuite
        Write-Output ("=" * 70)
        Write-Output "$($suite.name): $($suite.tests) test(s), $($suite.failures) failure(s), $($suite.errors) error(s), $($suite.skipped) skipped"
        foreach ($case in $suite.testcase) {
            $status = 'PASS'
            $detail = $null
            foreach ($kind in @('failure', 'error')) {
                $node = $case.SelectSingleNode($kind)
                if ($node) { $status = $kind.ToUpper(); $detail = $node.InnerText }
            }
            if ($case.SelectSingleNode('skipped')) { $status = 'SKIPPED'; $detail = $case.SelectSingleNode('skipped').message }
            Write-Output "  [$status] $($case.name)"
            if ($detail) {
                ($detail -split "`n" | Select-Object -First 40) | ForEach-Object { Write-Output "    $_" }
            }
        }
        foreach ($stream in @('system-out', 'system-err')) {
            $node = $suite.SelectSingleNode($stream)
            if ($node -and $node.InnerText.Trim()) {
                Write-Output "--- $stream ---"
                Write-Output $node.InnerText.TrimEnd()
            }
        }
    }
    exit $gradleExit
}
finally {
    Pop-Location
}
