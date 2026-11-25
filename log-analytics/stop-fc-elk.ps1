#!/usr/bin/env pwsh
#
# Stop the fc-elk stack.
#
#   .\stop-fc-elk.ps1                  stop the containers, keep everything else
#   .\stop-fc-elk.ps1 -v               ... and drop the ES/Logstash data volumes
#                                      (indexed logs + sincedb read-position) AND empty
#                                      the logs\ drop zone -- a full clean slate
#   .\stop-fc-elk.ps1 -v --keep-logs   full volume wipe, but leave the drop zone alone
#   .\stop-fc-elk.ps1 --logs           stop and empty the drop zone only (see the warning below)
#
# Why the drop zone is tied to -v: pull-logs.ps1 copies the WHOLE remote log each
# time, and incremental ingest works only because Logstash remembers a read
# offset (sincedb) for each file. Deleting the pulled files without dropping that
# offset means the next pull re-reads every line into an index that already has
# them -- duplicates. Wiping volumes and drop zone together keeps the three bits
# of state (indices, sincedb, pulled files) consistent.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ScriptDir = $PSScriptRoot
$LogsDir = Join-Path $ScriptDir 'logs'

$downArgs = @()
$wipeLogs = $false
$keepLogs = $false
$dropVolumes = $false
foreach ($a in $args) {
    switch ($a) {
        { $_ -in '-v', '--volumes' } { $wipeLogs = $true; $dropVolumes = $true; $downArgs += $a }
        '--logs'      { $wipeLogs = $true }   # not a compose flag -- don't forward
        '--keep-logs' { $keepLogs = $true }   # ditto
        default       { $downArgs += $a }     # anything else goes to `docker compose down`
    }
}
if ($keepLogs) { $wipeLogs = $false }

Push-Location ($ScriptDir)
try {
    docker compose down @downArgs
    if ($LASTEXITCODE -ne 0) { throw "docker compose down failed (exit $LASTEXITCODE)" }
}
finally {
    Pop-Location
}

if ($wipeLogs -and (Test-Path $LogsDir)) {
    # Everything except .gitignore, which is what keeps the (git-ignored) drop zone
    # in the repo. Top level only, so this can never reach outside the drop zone.
    $freed = (Get-ChildItem -LiteralPath $LogsDir -Recurse -File -Force |
              Where-Object { $_.Name -ne '.gitignore' } |
              Measure-Object -Property Length -Sum).Sum
    Get-ChildItem -LiteralPath $LogsDir -Force |
        Where-Object { $_.Name -ne '.gitignore' } |
        Remove-Item -Recurse -Force
    $mb = if ($freed) { " (freed ~{0:N0} MB)" -f ($freed / 1MB) } else { "" }
    Write-Host ">>> logs\ emptied$mb."
    if (-not $dropVolumes) {
        Write-Host "    NOTE: the sincedb volume was kept, so the next pull-logs.ps1 writes new"
        Write-Host "    files that Logstash reads from the beginning -- re-indexing lines that"
        Write-Host "    are already in Elasticsearch. Use -v for a consistent reset."
    }
}

Write-Host ">>> fc-elk stopped."
