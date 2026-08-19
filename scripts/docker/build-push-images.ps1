#!/usr/bin/env pwsh
#
# Build (and optionally push) the Frontcache container images. PowerShell twin of
# build-push-images.sh - see that file for the full rationale.
#
# The images are built FROM the distribution archives produced by `.\gradlew.bat dist`, not
# from the working tree, so an image is reproducible from a published artifact. The build
# context is a small staging directory assembled here.
#
#   .\build-push-images.ps1                              # version.properties, host arch, load locally
#   .\build-push-images.ps1 2.5.0 -Push                  # multi-arch, push 2.5.0 + 2.5
#   .\build-push-images.ps1 2.5.0 -Push -Latest          # ... and :latest
#   .\build-push-images.ps1 local -Only server           # just the two server images
#   .\build-push-images.ps1 2.5.0 -Push -DryRun          # print the buildx commands and stop
#
# Env knobs: NS, IMAGE_SERVER, IMAGE_CONSOLE, PLATFORMS, NO_BUILD=1
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Tag,
    [switch]$Push,
    [switch]$Latest,
    [switch]$DryRun,
    [ValidateSet('server', 'console', 'all')]
    [string]$Only = 'all'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ScriptDir = $PSScriptRoot
$RepoRoot  = (Resolve-Path (Join-Path $ScriptDir '..\..')).Path

# ---- configuration -----------------------------------------------------------
$Ns           = if ($env:NS)            { $env:NS }            else { 'pavlikovskiy' }
$ImageServer  = if ($env:IMAGE_SERVER)  { $env:IMAGE_SERVER }  else { "$Ns/frontcache-server" }
$ImageConsole = if ($env:IMAGE_CONSOLE) { $env:IMAGE_CONSOLE } else { "$Ns/frontcache-console" }
$Platforms    = if ($env:PLATFORMS)     { $env:PLATFORMS }     else { 'linux/amd64,linux/arm64' }
$Builder      = 'frontcache'

# the bundle version - always from version.properties, independent of the image tag
$FcVersion = (Get-Content (Join-Path $RepoRoot 'version.properties') |
              Where-Object { $_ -match '^version=' } |
              ForEach-Object { $_ -replace '^version=', '' }).Trim()
if ([string]::IsNullOrEmpty($FcVersion)) {
    Write-Error "ERROR: could not read version from version.properties"
    exit 1
}
if ([string]::IsNullOrEmpty($Tag)) { $Tag = $FcVersion }

# ---- tag list ----------------------------------------------------------------
# A semver tag also gets its X.Y alias, so 2.5.0 publishes 2.5 as well.
function Get-ExtraTags([string]$t, [string]$suffix = '') {
    $extra = @()
    if ($t -match '^(\d+)\.(\d+)\.(\d+)$') { $extra += "$($Matches[1]).$($Matches[2])$suffix" }
    if ($Latest) { $extra += "latest$suffix" }
    return $extra
}

# ---- 1. the archives ---------------------------------------------------------
$ServerTgz  = Join-Path $RepoRoot "frontcache-server\build\distributions\frontcache-server-$FcVersion.tar.gz"
$ConsoleTgz = Join-Path $RepoRoot "frontcache-console\build\distributions\frontcache-console-$FcVersion.tar.gz"

if ($env:NO_BUILD -eq '1') {
    Write-Host ">>> NO_BUILD=1 - using the archives already on disk"
} else {
    Write-Host ">>> Building the distribution archives (.\gradlew.bat dist) ..."
    Push-Location $RepoRoot
    try {
        & (Join-Path $RepoRoot 'gradlew.bat') 'dist'
        if ($LASTEXITCODE -ne 0) { throw "gradle dist failed (exit $LASTEXITCODE)" }
    } finally { Pop-Location }
}

if ($Only -ne 'console' -and -not (Test-Path $ServerTgz))  { Write-Error "ERROR: missing $ServerTgz";  exit 1 }
if ($Only -ne 'server'  -and -not (Test-Path $ConsoleTgz)) { Write-Error "ERROR: missing $ConsoleTgz"; exit 1 }

# ---- 2. buildx builder -------------------------------------------------------
$BuildxArgs = @()
if ($Push) {
    docker buildx inspect $Builder *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Host ">>> Creating the '$Builder' buildx builder (docker-container driver) ..."
        if (-not $DryRun) {
            docker buildx create --name $Builder --driver docker-container *> $null
            if ($LASTEXITCODE -ne 0) { throw "docker buildx create failed (exit $LASTEXITCODE)" }
        }
    }
    $BuildxArgs += @('--builder', $Builder, '--platform', $Platforms, '--push')
    Write-Host ">>> Multi-platform build ($Platforms), pushing to the registry"
} else {
    $BuildxArgs += '--load'
    Write-Host ">>> Single-platform build (host arch), loading into the local image store"
}

# ---- 3. build ----------------------------------------------------------------
$StageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("fc-image-ctx-" + [System.Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $StageRoot | Out-Null

function Build-Image {
    param(
        [string]$Name, [string]$SrcDir, [string]$Tarball, [string]$Archive,
        [string]$Image, [string]$ImageTag, [string[]]$Extras, [string]$Target = ''
    )
    $stage = Join-Path $StageRoot $Name
    New-Item -ItemType Directory -Path $stage | Out-Null
    Copy-Item (Join-Path $SrcDir 'Dockerfile') $stage
    Copy-Item (Join-Path $SrcDir '*.sh') $stage
    Copy-Item $Tarball (Join-Path $stage $Archive)

    # not $args: that is a PowerShell automatic variable
    $dockerArgs = @('buildx', 'build') + $BuildxArgs +
            @('--build-arg', "FRONTCACHE_VERSION=$FcVersion",
              '-f', (Join-Path $stage 'Dockerfile'), '-t', "${Image}:${ImageTag}")
    if ($Target) { $dockerArgs += @('--target', $Target) }
    foreach ($t in $Extras) { $dockerArgs += @('-t', "${Image}:${t}") }
    $dockerArgs += $stage

    $extraNote = if ($Extras) { " (also: $($Extras -join ', '))" } else { '' }
    Write-Host ">>> ${Image}:${ImageTag}$extraNote"
    if ($DryRun) {
        Write-Host "    docker $($dockerArgs -join ' ')"
    } else {
        docker @dockerArgs
        if ($LASTEXITCODE -ne 0) { throw "docker buildx build failed (exit $LASTEXITCODE)" }
    }
}

try {
    if ($Only -eq 'all' -or $Only -eq 'server') {
        Build-Image -Name 'server-full' -SrcDir (Join-Path $ScriptDir 'fc-server') `
            -Tarball $ServerTgz -Archive 'frontcache-server.tar.gz' `
            -Image $ImageServer -ImageTag $Tag -Extras (Get-ExtraTags $Tag) -Target 'full'
        Build-Image -Name 'server-slim' -SrcDir (Join-Path $ScriptDir 'fc-server') `
            -Tarball $ServerTgz -Archive 'frontcache-server.tar.gz' `
            -Image $ImageServer -ImageTag "$Tag-slim" -Extras (Get-ExtraTags $Tag '-slim') -Target 'slim'
    }
    if ($Only -eq 'all' -or $Only -eq 'console') {
        Build-Image -Name 'console' -SrcDir (Join-Path $ScriptDir 'fc-console') `
            -Tarball $ConsoleTgz -Archive 'frontcache-console.tar.gz' `
            -Image $ImageConsole -ImageTag $Tag -Extras (Get-ExtraTags $Tag)
    }
}
finally {
    Remove-Item -Recurse -Force $StageRoot -ErrorAction SilentlyContinue
}

Write-Host ""
if ($Push) {
    Write-Host ">>> Pushed. On the target host:"
    Write-Host "    docker run -d --name fc-server --restart unless-stopped ``"
    Write-Host "      -p 80:80 -p 443:443 -e ORIGIN_HOST=origin.example.com ``"
    Write-Host "      ${ImageServer}:${Tag}"
} else {
    Write-Host ">>> Loaded locally. Start the stack with:"
    Write-Host "    `$env:FC_VERSION='$Tag'; .\start-fc-server.ps1"
    Write-Host "    `$env:FC_VERSION='$Tag'; .\start-fc-server.ps1 -WithConsole"
}
