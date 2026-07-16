#!/usr/bin/env pwsh
#
# Build the fc-server image (nginx + Frontcache standalone) and push it to a
# registry (Docker Hub by default) so it can be pulled and run on a remote host.
#
#   .\build-push-fc-server.ps1 v1.0
#   .\build-push-fc-server.ps1 v1.0 -Latest          # also tag+push :latest
#   $env:IMAGE="myuser/frontcache"; .\build-push-fc-server.ps1 v1.0
#
# Equivalent to:
#   docker build -t pavlikovskiy/frontcache:v1.0 .
#   docker push  pavlikovskiy/frontcache:v1.0
#
# Steps:
#   1. build a fresh WAR + server/lib   (.\gradlew.bat :frontcache-server:build)
#   2. docker build the image           (context = repo root, fc-server Dockerfile)
#   3. docker push the tag(s)
#
# On the remote host:
#   docker run -d --name fc-server --restart unless-stopped `
#     -p 80:80 -p 443:443 `
#     -e ORIGIN_HOST=origin.example.com `
#     pavlikovskiy/frontcache:v1.0
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Tag,
    [switch]$Latest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# ---- configuration (env-overridable) ----------------------------------------
$Image    = if ($env:IMAGE)    { $env:IMAGE }    else { 'pavlikovskiy/frontcache' }  # Docker Hub repo (user/name)
$Platform = if ($env:PLATFORM) { $env:PLATFORM } else { 'linux/amd64' }              # remote hosts are usually amd64;
                                                                                     # build host may be arm64 (Apple Silicon)
# -----------------------------------------------------------------------------

if ([string]::IsNullOrEmpty($Tag)) {
    $name = Split-Path -Leaf $PSCommandPath
    Write-Error "usage: $name <tag> [-Latest]`n  e.g. $name v1.0"
    exit 1
}

$ScriptDir  = $PSScriptRoot
$RepoRoot   = (Resolve-Path (Join-Path $ScriptDir '..\..')).Path
$Dockerfile = Join-Path $ScriptDir 'fc-server\Dockerfile'

# ---- 1. build a fresh WAR (Dockerfile COPYs the assembled server tree) -------
Write-Host ">>> Building fresh WAR (.\gradlew.bat :frontcache-server:build) ..."
Push-Location $RepoRoot
try {
    & (Join-Path $RepoRoot 'gradlew.bat') ':frontcache-server:build'
    if ($LASTEXITCODE -ne 0) { throw "gradle build failed (exit $LASTEXITCODE)" }
}
finally {
    Pop-Location
}

$RootWar = Join-Path $RepoRoot 'frontcache-server\server\frontcache-base\webapps\ROOT.war'
if (-not (Test-Path $RootWar)) {
    Write-Error "ERROR: build did not produce $RootWar"
    exit 1
}

# ---- 2. build the image (context = repo root) --------------------------------
Write-Host ">>> Building image ${Image}:${Tag} ($Platform) ..."
docker build `
    --platform $Platform `
    -f $Dockerfile `
    -t "${Image}:${Tag}" `
    $RepoRoot
if ($LASTEXITCODE -ne 0) { throw "docker build failed (exit $LASTEXITCODE)" }

if ($Latest) {
    docker tag "${Image}:${Tag}" "${Image}:latest"
    if ($LASTEXITCODE -ne 0) { throw "docker tag failed (exit $LASTEXITCODE)" }
}

# ---- 3. push -----------------------------------------------------------------
# Requires a prior `docker login` for the target registry.
Write-Host ">>> Pushing ${Image}:${Tag} ..."
docker push "${Image}:${Tag}"
if ($LASTEXITCODE -ne 0) { throw "docker push failed (exit $LASTEXITCODE)" }
if ($Latest) {
    Write-Host ">>> Pushing ${Image}:latest ..."
    docker push "${Image}:latest"
    if ($LASTEXITCODE -ne 0) { throw "docker push (latest) failed (exit $LASTEXITCODE)" }
}

Write-Host ">>> Done. Pull & run on a remote host:"
Write-Host "    docker run -d --name fc-server --restart unless-stopped ``"
Write-Host "      -p 80:80 -p 443:443 -e ORIGIN_HOST=origin.example.com ``"
Write-Host "      ${Image}:${Tag}"
