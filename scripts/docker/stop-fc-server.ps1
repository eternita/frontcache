#!/usr/bin/env pwsh
#
# Stop and remove the fc-server container. Pass -v to also drop the cache/log volumes.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ScriptDir = $PSScriptRoot
Push-Location (Join-Path $ScriptDir 'fc-server')
try {
    docker compose down @args
    if ($LASTEXITCODE -ne 0) { throw "docker compose down failed (exit $LASTEXITCODE)" }
}
finally {
    Pop-Location
}
Write-Host ">>> fc-server stopped."
