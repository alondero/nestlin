<#
PowerShell equivalent of the opt-in documentation pre-commit hook.
Git for Windows normally executes .githooks/pre-commit through Git Bash;
this file is also useful for manual validation and native PowerShell setups.
#>
$ErrorActionPreference = 'Stop'

# Mapper dispatch lives in GamePak.kt and the canonical mapper documentation
# lives in MAPPER_SUPPORT.md. Touching either should also re-run the linter
# so the mapper ID consistency check cannot be bypassed by a Kotlin-only
# commit.
$staged = @(git diff --cached --name-only -- '*.md' '*.yml' '*.yaml' `
        'src/main/kotlin/com/github/alondero/nestlin/gamepak/GamePak.kt' `
        'MAPPER_SUPPORT.md')
if ($staged.Count -eq 0) { exit 0 }

$python = if ($env:PYTHON) { $env:PYTHON } else { 'python' }
& $python 'tools/docs_lint.py'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
