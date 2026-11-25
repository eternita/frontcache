#!/usr/bin/env pwsh
#
# Pull Frontcache logs from the FC servers into the logs\ drop zone beside it, where the
# Logstash container tails them.
#
# Hosts are ssh aliases / hostnames from your ~\.ssh\config (e.g. fc-us, fc-eu, or
# full FQDNs). Pass them as the first argument (space-separated) or via HOSTS:
#
#   .\pull-logs.ps1 "fc-us fc-eu"
#   $env:HOSTS="fc-us fc-eu"; .\pull-logs.ps1
#
# Unlike the Bash version (which uses rsync), this uses ssh + scp + tar. On Windows
# the rsync you install (cwRsync / MSYS2 / Git-Bash) talks to Windows' OpenSSH over
# pipes it can't drive cleanly, so transfers die with "connection unexpectedly
# closed (0 bytes received so far)". ssh/scp/tar are all Windows-native (tar.exe
# ships with Windows 10+), so this path just works wherever `ssh <host>` works.
#
# Log types pulled:
#   - frontcache-requests*.log  -> request logs (Frontcache Overview dashboard)
#   - error*.log                -> error logs (Frontcache Errors dashboard)
#   - fallback*.log             -> fallback logs (Frontcache Fallbacks dashboard)
#   - frontcache-failed-requests*.log
#                               -> rejected / failed request logs
#                                  (Frontcache Rejected Requests dashboard)
#
# For each host it: tars the current log + rolled .zip archives on the remote
# (under sudo, since logs are owned by the frontcache service user), scp's the
# tarball down, extracts it, unzips the rolled archives, prefixes every file with
# the host alias (so hosts never collide), and drops them into logs\.
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Hosts
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ScriptDir = $PSScriptRoot

# ---- configuration (env-overridable) ----------------------------------------
if ([string]::IsNullOrEmpty($Hosts)) { $Hosts = $env:HOSTS }
# Where the logs are on the remote host. Unset, it is auto-detected per host from
# $LogDirCandidates -- Frontcache writes to $FRONTCACHE_HOME/logs, and where that is
# depends on how it was installed. Set REMOTE_LOG_DIR to pin it (a host that then
# does not have it is an error, not a fallback). '~' is expanded by the remote shell.
$RemoteLogDir = if ($env:REMOTE_LOG_DIR) { $env:REMOTE_LOG_DIR } else { '' }
$LogDirCandidates = @(
    '/opt/frontcache/FRONTCACHE_HOME/logs'           # installer script (the default --dir)
    '~/opt/frontcache-server/FRONTCACHE_HOME/logs'   # archive unpacked into the ssh user's home
    '/opt/frontcache-server/FRONTCACHE_HOME/logs'    # archive unpacked under /opt
    '/opt/frontcache/logs'                           # logs symlinked/relocated out of FRONTCACHE_HOME
)
$DestDir      = if ($env:DEST_DIR)       { $env:DEST_DIR }       else { Join-Path $ScriptDir 'logs' }
$StageRoot    = if ($env:STAGE_DIR)      { $env:STAGE_DIR }      else { Join-Path ([System.IO.Path]::GetTempPath()) 'fc-pull-logs' }
# The systemd unit runs Frontcache as the ssh user itself (User=$REMOTE_USER), so that
# user owns its own logs and the remote tar reads them directly. Set RSYNC_SUDO=1 only
# for a deployment running under a different service account -- and note it then needs
# passwordless sudo, since this ssh session has no tty to prompt on.
$RsyncSudo    = if ($env:RSYNC_SUDO)     { $env:RSYNC_SUDO }     else { '0' }
# ssh / scp binaries (override to force a specific OpenSSH, e.g.
# $env:SSH_CMD = "C:\Windows\System32\OpenSSH\ssh.exe").
$SshCmd       = if ($env:SSH_CMD)        { $env:SSH_CMD }        else { 'ssh' }
$ScpCmd       = if ($env:SCP_CMD)        { $env:SCP_CMD }        else { 'scp' }
# -----------------------------------------------------------------------------

if ([string]::IsNullOrEmpty($Hosts)) {
    $name = Split-Path -Leaf $PSCommandPath
    Write-Error "ERROR: no hosts given. Usage: $name `"fc-us fc-eu`"  (ssh aliases from ~\.ssh\config)"
    exit 1
}

foreach ($bin in @($SshCmd, $ScpCmd, 'tar')) {
    if (-not (Get-Command $bin -ErrorAction SilentlyContinue)) {
        Write-Error "ERROR: '$bin' not found on PATH. ssh/scp ship with Windows OpenSSH; tar ships with Windows 10+."
        exit 1
    }
}

$sudo = if ($RsyncSudo -eq '1') { 'sudo ' } else { '' }

New-Item -ItemType Directory -Force -Path $DestDir | Out-Null

# Log file name patterns to pull from the remote server (used by find -name).
$findPatterns = @(
    'frontcache-requests\*',
    'error\*',
    'fallback\*',
    # rejected/failed requests: written by the 'frontcache.failed-requests' logger.
    # NOTE: this name does not match 'frontcache-requests*', so it needs its own
    # pattern -- and the fc-requests Logstash pipeline likewise never sees it.
    'frontcache-failed-requests\*'
)

$failedHosts = @()

foreach ($hostAlias in ($Hosts -split '\s+' | Where-Object { $_ })) {
    # Resolve the remote log directory: the pinned REMOTE_LOG_DIR, or the first
    # candidate that exists on this host. One remote shell loop, so it costs a
    # single ssh round-trip.
    if ($RemoteLogDir) {
        $hostLogDir = $RemoteLogDir
    }
    else {
        # backtick-$ keeps $d out of PowerShell's expansion so the remote shell sees it
        $probe = "for d in $($LogDirCandidates -join ' '); do [ -d `$d ] && echo `$d && break; done"
        # a native command writing to stderr is a terminating error under
        # ErrorActionPreference='Stop', and ssh does that for banners/warnings
        $prevProbeEAP = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try   { $hostLogDir = (& $SshCmd $hostAlias $probe 2>$null | Select-Object -First 1) }
        finally { $ErrorActionPreference = $prevProbeEAP }
        if ($hostLogDir) { $hostLogDir = $hostLogDir.Trim() }
    }
    if ([string]::IsNullOrWhiteSpace($hostLogDir)) {
        Write-Host "!!! [$hostAlias] no Frontcache log dir found. Tried:"
        $LogDirCandidates | ForEach-Object { Write-Host "      $_" }
        Write-Host "    re-run with: `$env:REMOTE_LOG_DIR='<dir>'"
        $failedHosts += $hostAlias
        continue
    }

    Write-Host ">>> [$hostAlias] pulling logs from $hostLogDir ..."
    $hostStage = Join-Path $StageRoot $hostAlias
    if (Test-Path $hostStage) { Remove-Item -Recurse -Force $hostStage }
    New-Item -ItemType Directory -Force -Path $hostStage | Out-Null

    $remoteTar = "/tmp/fc-pull-logs-$hostAlias.tgz"
    $localTgz  = Join-Path $hostStage 'logs.tgz'

    # Native commands that write to stderr become terminating errors under
    # ErrorActionPreference='Stop'; drop to 'Continue' so one host failing (or a
    # host with no matching logs) doesn't abort the whole run.
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # 1. build a tarball on the remote. `find | tar --files-from=-` tolerates
        #    "no matches" (empty archive) instead of erroring on a literal glob.
        #    \* keeps the star out of the remote shell so find does the matching.
        #    Multiple -name clauses cover request, error, and fallback logs.
        $nameClauses = ($findPatterns | ForEach-Object { "-name $_" }) -join ' -o '
        $buildCmd = "cd $hostLogDir && ${sudo}find . -maxdepth 1 \( $nameClauses \) -print0 | ${sudo}tar --null -czf $remoteTar --files-from=- && ${sudo}chmod 644 $remoteTar"
        & $SshCmd $hostAlias $buildCmd

        # 2. fetch it (staged as a file so PowerShell never pipes the binary stream)
        & $ScpCmd "${hostAlias}:$remoteTar" $localTgz

        # 3. remove the remote tarball
        & $SshCmd $hostAlias "${sudo}rm -f $remoteTar" 2>&1 | Out-Null
    }
    finally {
        $ErrorActionPreference = $prevEAP
    }

    if (-not (Test-Path $localTgz)) {
        Write-Host ">>> [$hostAlias] no tarball fetched (connection failed, or no logs) -- skipping."
        $failedHosts += $hostAlias
        continue
    }

    # 4. extract the tarball locally (Windows bsdtar handles .tgz)
    & tar -xzf $localTgz -C $hostStage
    Remove-Item -Force $localTgz

    # 5. unzip rolled archives (logback rolls to <name>-<date>.log.zip)
    foreach ($z in (Get-ChildItem -Path $hostStage -Filter '*.zip' -File -ErrorAction SilentlyContinue)) {
        Expand-Archive -Path $z.FullName -DestinationPath $hostStage -Force
        Remove-Item -Force $z.FullName
    }

    # 6. publish every .log to the drop zone, prefixed with the host alias
    $count = 0
    foreach ($f in (Get-ChildItem -Path $hostStage -Filter '*.log' -File -ErrorAction SilentlyContinue)) {
        Copy-Item -Force -Path $f.FullName -Destination (Join-Path $DestDir "$hostAlias-$($f.Name)")
        $count++
    }

    Write-Host ">>> [$hostAlias] $count log file(s) -> $DestDir"
}

if ($failedHosts.Count -gt 0) {
    Write-Host ">>> FAILED for: $($failedHosts -join ' ')"
    exit 1
}

$kibanaPort = if ($env:KIBANA_PORT) { $env:KIBANA_PORT } else { '5601' }
Write-Host ">>> Done. Logstash (fc-elk) will pick up new files from $DestDir."
Write-Host "    Open Kibana on :$kibanaPort to browse."
Write-Host "    Dashboards:"
Write-Host "      Frontcache Overview  — /app/dashboards#/view/fc-overview"
Write-Host "      Frontcache Errors    — /app/dashboards#/view/fc-errors"
Write-Host "      Frontcache Fallbacks — /app/dashboards#/view/fc-fallbacks"
Write-Host "      Frontcache Rejected Requests — /app/dashboards#/view/fc-rejected"

