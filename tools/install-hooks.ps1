<# Install the repository's cross-platform Git hooks for this checkout. #>
$ErrorActionPreference = 'Stop'
git config core.hooksPath .githooks
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output 'Installed repository hooks via core.hooksPath=.githooks'
