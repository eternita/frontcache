#!/usr/bin/env pwsh
#
# Build the images from this checkout and start the Frontcache stack.
#
# 1. builds the distribution archives + the images, tagged :local
# 2. starts the compose project
#
# Config comes from fc-server\.env (copy fc-server\.env.example first).
#
#   .\start-fc-server.ps1                 # server only
#   .\start-fc-server.ps1 -WithConsole    # server + console (loopback :7080)
#   .\start-fc-server.ps1 -NoBuild        # reuse the images already loaded
[CmdletBinding()]
param(
    [switch]$WithConsole,
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ScriptDir  = $PSScriptRoot
$ComposeDir = Join-Path $ScriptDir 'fc-server'

# The compose file runs published images by tag, so a local run needs locally-built ones.
# ':local' is what .env.example ships as FC_VERSION.
if ($NoBuild) {
    Write-Host ">>> -NoBuild: using the images already in the local store"
} else {
    Write-Host ">>> Building the :local images ..."
    & (Join-Path $ScriptDir 'build-push-images.ps1') 'local'
    if ($LASTEXITCODE -ne 0) { throw "image build failed (exit $LASTEXITCODE)" }
}

$composeArgs = @()
if ($WithConsole) { $composeArgs += @('--profile', 'console') }

Write-Host ">>> Starting the stack ..."
Push-Location $ComposeDir
try {
    docker compose @composeArgs up -d
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed (exit $LASTEXITCODE)" }

    # Ask compose for the published port rather than re-reading .env here.
    $fcPort  = if ($env:FRONTCACHE_HTTP_PORT) { $env:FRONTCACHE_HTTP_PORT } else { '9080' }
    $http    = (docker compose port fc-server $fcPort) 2>$null
    $console = if ($WithConsole) { (docker compose port fc-console 7080) 2>$null } else { $null }
}
finally {
    Pop-Location
}

Write-Host ""
Write-Host ">>> Frontcache is up."
Write-Host "    http://$http  (plain HTTP - no TLS here; put a front door in front of it,"
Write-Host "     e.g. examples/front-door in the public repo)"
if ($console) { Write-Host "    console: http://$console/" }
Write-Host "    health:  docker inspect --format '{{.State.Health.Status}}' fc-server"
Write-Host "    logs:    docker logs -f fc-server"
