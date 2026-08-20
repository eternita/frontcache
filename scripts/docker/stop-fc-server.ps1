#!/usr/bin/env pwsh
#
# Stop and remove the Frontcache stack. Pass -v to also drop the conf/cache/log volumes.
#
# --profile console is always passed so `down` removes the console too, whether or not it
# was started; without it compose leaves a profiled service running.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ScriptDir = $PSScriptRoot
Push-Location (Join-Path $ScriptDir 'fc-server')
try {
    docker compose --profile console down @args
    if ($LASTEXITCODE -ne 0) { throw "docker compose down failed (exit $LASTEXITCODE)" }
}
finally {
    Pop-Location
}
Write-Host ">>> Frontcache stack stopped."
