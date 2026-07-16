#!/usr/bin/env pwsh
#
# Build and start the fc-server container (nginx + Frontcache standalone).
#
# 1. builds a fresh WAR + server/lib   (.\gradlew.bat :frontcache-server:build)
# 2. builds the fc-server image        (docker compose build)
# 3. starts it                         (docker compose up -d)
#
# Config comes from fc-server\.env (copy fc-server\.env.example first).
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ScriptDir  = $PSScriptRoot
$RepoRoot   = (Resolve-Path (Join-Path $ScriptDir '..\..')).Path
$ComposeDir = Join-Path $ScriptDir 'fc-server'

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

Write-Host ">>> Building and starting fc-server ..."
Push-Location $ComposeDir
try {
    docker compose build
    if ($LASTEXITCODE -ne 0) { throw "docker compose build failed (exit $LASTEXITCODE)" }
    docker compose up -d
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed (exit $LASTEXITCODE)" }
}
finally {
    Pop-Location
}

$httpPort  = if ($env:FC_HTTP_PORT)  { $env:FC_HTTP_PORT }  else { '80' }
$httpsPort = if ($env:FC_HTTPS_PORT) { $env:FC_HTTPS_PORT } else { '443' }

Write-Host ">>> fc-server is up."
Write-Host "    http://localhost:$httpPort   https://localhost:$httpsPort (self-signed)"
Write-Host "    logs: docker logs -f fc-server"
