<#
PowerShell equivalent of the opt-in documentation pre-commit hook.
Git for Windows normally executes .githooks/pre-commit through Git Bash;
this file is also useful for manual validation and native PowerShell setups.
#>
$ErrorActionPreference = 'Stop'

$staged = @(git diff --cached --name-only -- '*.md' '*.yml' '*.yaml')
if ($staged.Count -eq 0) { exit 0 }

$python = if ($env:PYTHON) { $env:PYTHON } else { 'python' }
& $python 'tools/docs_lint.py'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
