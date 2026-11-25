#!/usr/bin/env pwsh
#
# Start the fc-elk stack (Elasticsearch + Kibana + Logstash).
#
# Config comes from .env beside this script (copy .env.example first).
# After it is up, pull logs with .\pull-logs.ps1 and open Kibana on :5601.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ScriptDir  = $PSScriptRoot
$EsPort     = if ($env:ES_PORT)     { $env:ES_PORT }     else { '9200' }
$KibanaPort = if ($env:KIBANA_PORT) { $env:KIBANA_PORT } else { '5601' }

Push-Location ($ScriptDir)
try {
    docker compose up -d
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed (exit $LASTEXITCODE)" }
}
finally {
    Pop-Location
}

# Apply the Frontcache index templates (maps geoip.location -> geo_point, numerics,
# keyword enums) before any logs are indexed. Wait for ES to answer first.
Write-Host ">>> Waiting for Elasticsearch on :$EsPort ..."
for ($i = 0; $i -lt 60; $i++) {
    try {
        Invoke-RestMethod -Uri "http://localhost:$EsPort/_cluster/health" -TimeoutSec 5 | Out-Null
        break
    }
    catch {
        Start-Sleep -Seconds 2
    }
}

foreach ($tpl in @('frontcache', 'frontcache-errors', 'frontcache-fallbacks', 'frontcache-rejected')) {
    Write-Host ">>> Applying $tpl index template ..."
    $templateFile = Join-Path $ScriptDir "elasticsearch\${tpl}-index-template.json"
    try {
        Invoke-RestMethod -Method Put -Uri "http://localhost:$EsPort/_index_template/$tpl" `
            -ContentType 'application/json' `
            -InFile $templateFile | Out-Null
        Write-Host "    template applied."
    }
    catch {
        # Surface the ES response body (it explains rejections, e.g. an
        # index-pattern/priority overlap between templates) instead of hiding it.
        $detail = $_.Exception.Message
        try { $detail = $_.ErrorDetails.Message } catch { }
        Write-Host "    WARNING: $tpl template not applied: $detail"
    }
}

# Import the data views + dashboards (Overview, Errors, Fallbacks, Rejected
# Requests) into Kibana.
# Idempotent (overwrite=true), so it re-imports on every start -- this is what
# restores the dashboards after a `stop-fc-elk.ps1 -v` (which drops the ES volume
# where Kibana stores saved objects). Wait for Kibana's API to come up first.
Write-Host ">>> Waiting for Kibana on :$KibanaPort ..."
for ($i = 0; $i -lt 90; $i++) {
    try {
        Invoke-RestMethod -Uri "http://localhost:$KibanaPort/api/status" -TimeoutSec 5 | Out-Null
        break
    }
    catch {
        Start-Sleep -Seconds 2
    }
}

foreach ($dash in @('fc-dashboard', 'fc-errors-dashboard', 'fc-fallbacks-dashboard', 'fc-rejected-dashboard')) {
    Write-Host ">>> Importing Kibana dashboard ($dash) ..."
    $dashboardFile = Join-Path $ScriptDir "kibana\${dash}.ndjson"
    try {
        # Multipart file upload: PowerShell 6+ supports -Form for multipart/form-data.
        Invoke-RestMethod -Method Post `
            -Uri "http://localhost:$KibanaPort/api/saved_objects/_import?createNewCopies=false&overwrite=true" `
            -Headers @{ 'kbn-xsrf' = 'true' } `
            -Form @{ file = Get-Item $dashboardFile } | Out-Null
        Write-Host "    $dash imported."
    }
    catch {
        Write-Host "    WARNING: $dash import failed (is Kibana ready?)."
    }
}

Write-Host ">>> fc-elk is up."
Write-Host "    Dashboards:"
Write-Host "      Overview:  http://localhost:$KibanaPort/app/dashboards#/view/fc-overview"
Write-Host "      Errors:    http://localhost:$KibanaPort/app/dashboards#/view/fc-errors"
Write-Host "      Fallbacks: http://localhost:$KibanaPort/app/dashboards#/view/fc-fallbacks"
Write-Host "      Rejected:  http://localhost:$KibanaPort/app/dashboards#/view/fc-rejected"
Write-Host "    Elasticsearch: http://localhost:$EsPort"
Write-Host ""
Write-Host "    Pull logs from your FC hosts, then refresh the dashboards:"
Write-Host "      .\pull-logs.ps1 `"fc-us fc-eu`""
