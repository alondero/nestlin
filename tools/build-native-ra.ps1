# Builds the native RetroAchievements shared library for the current host
# platform. Invoked by Gradle's :buildNative task and by tools/ for local
# verification.
#
# Output:
#   - librcheevos_facade.so   (Linux)
#   - librcheevos_facade.dylib (macOS)
#   - rcheevos_facade.dll     (Windows)
#
# The artifact name matches JNA's standard mapping so the JNA-side Library
# name "rcheevos_facade" picks up the right artifact automatically.
#
# Exit codes:
#   0   built successfully
#   1   compiler or build failure
#   2   no supported compiler found (Gradle treats this as "build skipped")
param(
    [Parameter(Mandatory=$true)][string]$OutputDir,
    [Parameter(Mandatory=$true)][string]$RchDir,
    [Parameter(Mandatory=$true)][string]$FacadeDir,
    [string]$Compiler = "auto"
)

$ErrorActionPreference = 'Continue'

function Find-Compiler($name) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    # Windows-specific common install paths
    foreach ($p in @(
        "C:\ProgramData\mingw64\mingw64\bin\$name.exe",
        "C:\msys64\mingw64\bin\$name.exe",
        "C:\TDM-GCC-64\bin\$name.exe",
        "/usr/bin/$name",
        "/usr/local/bin/$name"
    )) {
        if (Test-Path $p) { return $p }
    }
    return $null
}

# Resolve compiler
$useCompiler = $null
if ($Compiler -eq 'auto' -or $Compiler -eq 'gcc') {
    $useCompiler = Find-Compiler 'gcc'
}
if (-not $useCompiler -and ($Compiler -eq 'auto' -or $Compiler -eq 'clang')) {
    $useCompiler = Find-Compiler 'clang'
}

if (-not $useCompiler) {
    Write-Host "[BUILD-NATIVE-RA] No supported C compiler found (gcc, clang). Native RA build skipped." -ForegroundColor Yellow
    exit 2
}

Write-Host "[BUILD-NATIVE-RA] Compiler: $useCompiler"
Write-Host "[BUILD-NATIVE-RA] Output:   $OutputDir"

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$objDir = Join-Path $OutputDir 'obj'
New-Item -ItemType Directory -Force -Path $objDir | Out-Null

# rcheevos sources (relative to rcheevos/src)
$rchSources = @(
    'rapi\rc_api_common.c','rapi\rc_api_editor.c','rapi\rc_api_info.c','rapi\rc_api_runtime.c','rapi\rc_api_user.c',
    'rc_client.c','rc_client_external.c','rc_compat.c','rc_util.c','rc_version.c',
    'rcheevos\alloc.c','rcheevos\condition.c','rcheevos\condset.c','rcheevos\consoleinfo.c','rcheevos\format.c',
    'rcheevos\lboard.c','rcheevos\memref.c','rcheevos\operand.c','rcheevos\rc_validate.c','rcheevos\richpresence.c',
    'rcheevos\runtime.c','rcheevos\runtime_progress.c','rcheevos\trigger.c','rcheevos\value.c',
    'rhash\aes.c','rhash\cdreader.c','rhash\hash.c','rhash\hash_disc.c','rhash\hash_encrypted.c',
    'rhash\hash_rom.c','rhash\hash_zip.c','rhash\md5.c'
)

$srcDir   = Join-Path $RchDir 'src'
$incDir   = Join-Path $RchDir 'include'

# Determine host platform → output library name and link flags
$isWindows = ($env:OS -eq 'Windows_NT') -or $IsWindows
$isMac     = $IsMacOS
if ($isWindows) {
    $libName  = 'rcheevos_facade.dll'
    $linkExt  = @()
    $linkLibs = @('-lws2_32')
} elseif ($isMac) {
    $libName  = 'librcheevos_facade.dylib'
    $linkExt  = @('-fPIC', '-dynamiclib')
    $linkLibs = @()
} else {
    $libName  = 'librcheevos_facade.so'
    $linkExt  = @('-fPIC')
    $linkLibs = @('-lpthread', '-ldl')
}

# Compile each rcheevos source (caching by mtime)
$objFiles = @()
foreach ($src in $rchSources) {
    $objName = ($src -replace '\\','_') -replace '\.c$','.o'
    $objPath = Join-Path $objDir $objName
    $srcPath = Join-Path $srcDir $src
    $objFiles += $objPath

    if ((Test-Path $objPath) -and ((Get-Item $objPath).LastWriteTime -gt (Get-Item $srcPath).LastWriteTime)) {
        Write-Host "[BUILD-NATIVE-RA] cached: $src"
        continue
    }
    Write-Host "[BUILD-NATIVE-RA] cc: $src"
    & $useCompiler -c -std=c99 -O2 -fvisibility=hidden -DRC_CLIENT_SUPPORTS_HASH=1 `
        "-I$incDir" "-I$srcDir" "-I$srcDir\rcheevos" "-I$srcDir\rhash" "-I$srcDir\rapi" `
        $srcPath -o $objPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $objPath)) {
        Write-Host "[BUILD-NATIVE-RA] FAIL: $src (exit=$LASTEXITCODE)" -ForegroundColor Red
        exit 1
    }
}

# Compile the façade
$facObj = Join-Path $objDir 'ra_facade.o'
$objFiles += $facObj
$facSrc = Join-Path $FacadeDir 'ra_facade.c'
if (-not (Test-Path $facObj) -or ((Get-Item $facObj).LastWriteTime -lt (Get-Item $facSrc).LastWriteTime)) {
    Write-Host "[BUILD-NATIVE-RA] cc: ra_facade.c"
    & $useCompiler -c -std=c99 -O2 -fvisibility=default -DRA_FACADE_BUILDING=1 -DRC_CLIENT_SUPPORTS_HASH=1 `
        "-I$FacadeDir" "-I$incDir" "-I$srcDir" `
        $facSrc -o $facObj
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $facObj)) {
        Write-Host "[BUILD-NATIVE-RA] FAIL: ra_facade.c (exit=$LASTEXITCODE)" -ForegroundColor Red
        exit 1
    }
}

# Link into the shared library
$libPath = Join-Path $OutputDir $libName
Write-Host "[BUILD-NATIVE-RA] link: $libPath"
$linkArgs = @('-shared') + $linkExt + @('-o', $libPath) + $objFiles + $linkLibs
& $useCompiler @linkArgs
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $libPath)) {
    Write-Host "[BUILD-NATIVE-RA] LINK FAILED (exit=$LASTEXITCODE)" -ForegroundColor Red
    exit 1
}

$libSize = (Get-Item $libPath).Length
Write-Host "[BUILD-NATIVE-RA] Built: $libPath ($libSize bytes)" -ForegroundColor Green

# Emit a per-platform manifest fragment (issue #273 AC: runtime validates
# checksum + pinned rcheevos version). The Gradle :buildNative task picks
# up this file alongside the .dll / .so / .dylib and merges the per-platform
# fragments into a single MANIFEST.json that ships in the runnable JAR.
#
# Fields:
#   platformId      - the canonical id RaManifest.currentPlatformId() emits
#   libraryFilename - the file JNA resolves at Native.load time
#   resourcePath    - the JAR-relative slash path
#   sha256Hex       - lowercase 64-char hex digest of the library bytes
#   sizeBytes       - library size in bytes (cheap pre-check vs. corruption)
$platformId = if ($isWindows) { "windows-x86_64" }
              elseif ($isMac) { "macos-universal" }
              else { "linux-x86_64" }
$resourcePath = "native-ra/" + ($libName -replace '^libr','') -replace '\.(dll|so|dylib)$',''
$resourcePath = "native-ra/" + (if ($isWindows) { "windows" } elseif ($isMac) { "macos" } else { "linux" }) + "/" + $libName
$sha256 = (Get-FileHash -Path $libPath -Algorithm SHA256).Hash.ToLower()
$manifestPath = Join-Path $OutputDir 'MANIFEST.fragment.json'
$fragment = @"
{
  "platforms": [
    {
      "platformId": "$platformId",
      "libraryFilename": "$libName",
      "resourcePath": "$resourcePath",
      "sha256Hex": "$sha256",
      "sizeBytes": $libSize
    }
  ]
}
"@
Set-Content -Path $manifestPath -Value $fragment -Encoding UTF8
Write-Host "[BUILD-NATIVE-RA] Wrote manifest fragment: $manifestPath"

exit 0
