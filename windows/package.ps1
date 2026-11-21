$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$staging = Join-Path $root 'build/windows-package/ClipClean'
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $staging, $dist | Out-Null

Push-Location $root
try {
    go build -trimpath -ldflags '-H=windowsgui' -o (Join-Path $staging 'ClipClean.exe') ./windows
    if ($LASTEXITCODE -ne 0) { throw 'Failed to build ClipClean.exe.' }
    Compress-Archive -LiteralPath $staging -DestinationPath (Join-Path $dist 'ClipClean-Windows.zip') -Force
} finally {
    Pop-Location
}
