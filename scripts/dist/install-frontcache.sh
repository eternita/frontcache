#!/usr/bin/env bash
#
# Install or upgrade Frontcache on this host from a published distribution archive.
#
# Runs ON THE TARGET MACHINE. It needs no repo checkout, no Gradle and no ssh - it downloads
# the release archive, verifies its checksum, and installs it as a systemd service. This is
# the customer path; scripts/dev/deploy-build-remote.sh is the developer's
# push-from-my-laptop tool and is not this.
#
# Download it, check it, then run it - deliberately NOT `curl | sudo bash`, because the
# checksum step below is the whole point:
#
#   V=2.5.0
#   BASE=https://repo.eternita.co/maven2/org/frontcache/frontcache-server/$V
#   curl -fLO $BASE/frontcache-server-$V-installer.sh
#   curl -fLO $BASE/frontcache-server-$V-installer.sh.sha256
#   # the published checksum may be a bare hash, so compare the hash field, not `shasum -c`:
#   [ "$(shasum -a 256 frontcache-server-$V-installer.sh | cut -d' ' -f1)" \
#     = "$(cut -d' ' -f1 < frontcache-server-$V-installer.sh.sha256)" ] && echo OK
#   sudo bash frontcache-server-$V-installer.sh --version $V \
#        --origin-host origin.example.com
#
# It installs Frontcache and nothing else. Frontcache speaks plain HTTP and terminates no TLS,
# so put your own front door on 80/443 in front of it - an ALB, Cloudflare, or nginx. A worked
# nginx front door (a script for exactly this layout) is in the public repo under
# examples/front-door.
#
# Usage:
#   install-frontcache.sh [options]
#   install-frontcache.sh uninstall [--yes]
#
# Options:
#   --version VER        release to install (default: the current release, resolved from the
#                        repository's maven-metadata.xml)
#   --archive PATH       install from a local .tar.gz instead of downloading (air-gapped
#                        hosts, or a release you staged yourself). Verified against
#                        PATH.sha256 when that file exists.
#   --console-archive P  the same, for the console bundle (see --with-console)
#   --dir DIR            install root (default /opt). Creates DIR/frontcache-server-VER and
#                        the DIR/frontcache symlink.
#   --user USER          service account to create and run as (default frontcache)
#   --port PORT          Frontcache HTTP port (default 9080)
#   --origin-host HOST   backend for cache misses; written into frontcache.properties
#   --origin-port PORT   origin https port (default 443)
#   --with-runtime       install the bundle that CARRIES ITS OWN Java runtime, picked for this
#                        host's platform, and skip the JDK step entirely. No JDK is installed
#                        or required. Adds ~50 MB to the download.
#   --with-console       also install the console (its own service on 7080, loopback only)
#   --skip-jdk           do not touch the JDK; one that can run Java 25 is already present
#   --dry-run            print what would happen and change nothing
#   --yes                do not prompt (required for unattended uninstall)
#
# Upgrades are safe to re-run: the new version is unpacked beside the old one, YOUR config is
# carried over untouched, and the symlink flip is the only cutover. Rollback is one symlink.
set -euo pipefail

MAVEN_BASE="https://repo.eternita.co/maven2/org/frontcache"
SERVICE_NAME="frontcache"
CONSOLE_SERVICE_NAME="frontcache-console"

# ---- defaults ----------------------------------------------------------------
VERSION=""
ARCHIVE=""
CONSOLE_ARCHIVE=""
INSTALL_DIR="/opt"
SERVICE_USER="frontcache"
FC_PORT="9080"
ORIGIN_HOST=""
ORIGIN_PORT="443"
WITH_CONSOLE=0
WITH_RUNTIME=0
RUNTIME_TARGET=""
SKIP_JDK=0
DRY_RUN=0
ASSUME_YES=0
MODE="install"

# ---- args --------------------------------------------------------------------
[ $# -gt 0 ] && [ "$1" = "uninstall" ] && { MODE="uninstall"; shift; }

while [ $# -gt 0 ]; do
  case "$1" in
    --version)      VERSION="${2:?--version needs a value}"; shift ;;
    --archive)      ARCHIVE="${2:?--archive needs a path}"; shift ;;
    --console-archive) CONSOLE_ARCHIVE="${2:?--console-archive needs a path}"; shift ;;
    --dir)          INSTALL_DIR="${2:?--dir needs a path}"; shift ;;
    --user)         SERVICE_USER="${2:?--user needs a name}"; shift ;;
    --port)         FC_PORT="${2:?--port needs a value}"; shift ;;
    --origin-host)  ORIGIN_HOST="${2:?--origin-host needs a value}"; shift ;;
    --origin-port)  ORIGIN_PORT="${2:?--origin-port needs a value}"; shift ;;
    --with-runtime) WITH_RUNTIME=1 ;;
    --with-console) WITH_CONSOLE=1 ;;
    --skip-jdk)     SKIP_JDK=1 ;;
    --dry-run)      DRY_RUN=1 ;;
    --yes|-y)       ASSUME_YES=1 ;;
    -h|--help)      sed -n '2,44p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)              echo >&2 "unknown option: $1  (try --help)"; exit 1 ;;
  esac
  shift
done

# ---- helpers -----------------------------------------------------------------
# All progress output goes to STDERR. That is not cosmetic: functions below return values
# through stdout via $(...), and a log line on stdout would be captured as part of the value.
log()  { printf '>>> %s\n' "$*" >&2; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Every mutating command goes through run(), so --dry-run is honest rather than approximate.
run() {
  if [ "$DRY_RUN" = 1 ]; then
    { printf '    [dry-run]'; printf ' %q' "$@"; printf '\n'; } >&2
  else
    "$@"
  fi
}

# For shell pipelines/redirections that run() cannot wrap.
run_sh() {
  if [ "$DRY_RUN" = 1 ]; then
    printf '    [dry-run] sh -c %q\n' "$1" >&2
  else
    bash -c "$1"
  fi
}

need_root() {
  [ "$(id -u)" = "0" ] || die "must run as root (use sudo)"
}

have() { command -v "$1" > /dev/null 2>&1; }

confirm() {
  [ "$ASSUME_YES" = 1 ] && return 0
  [ "$DRY_RUN" = 1 ] && return 0
  printf '%s [y/N] ' "$1"
  local reply
  read -r reply
  case "$reply" in [yY]|[yY][eE][sS]) return 0 ;; *) return 1 ;; esac
}

# systemd is absent in containers and chroots. That is not a reason to fail the whole
# install - the files are still correct and can be started by hand - so we warn and skip.
HAVE_SYSTEMD=0
have systemctl && [ -d /run/systemd/system ] && HAVE_SYSTEMD=1

# ---- uninstall ---------------------------------------------------------------
if [ "$MODE" = "uninstall" ]; then
  need_root
  log "Uninstalling Frontcache from $INSTALL_DIR"
  confirm "Stop the services and remove $INSTALL_DIR/frontcache* and the '$SERVICE_USER' user?" \
    || die "aborted"

  for svc in "$SERVICE_NAME" "$CONSOLE_SERVICE_NAME"; do
    if [ "$HAVE_SYSTEMD" = 1 ] && systemctl list-unit-files 2>/dev/null | grep -q "^${svc}.service"; then
      run systemctl stop "$svc" || true
      run systemctl disable "$svc" || true
    fi
    # unconditional: install writes the unit whether or not systemd is running (containers,
    # chroots, images being baked), so removal must not be conditional on it either
    run rm -f "/etc/systemd/system/${svc}.service"
  done
  [ "$HAVE_SYSTEMD" = 1 ] && run systemctl daemon-reload || true

  # Config and logs are deliberately NOT deleted: they are the operator's data. The paths are
  # printed so removing them stays a conscious act.
  run rm -f "$INSTALL_DIR/frontcache" "$INSTALL_DIR/frontcache-console"
  log "Services removed. Left in place on purpose:"
  log "    $INSTALL_DIR/frontcache-server-*   (config, cache, logs)"
  log "    $INSTALL_DIR/frontcache-console-*"
  log "    the '$SERVICE_USER' user"
  log "Remove them yourself once you are sure you want the data gone."
  exit 0
fi

# ---- preflight ---------------------------------------------------------------
need_root
have tar || die "need tar to unpack the release"

# A downloader is only required if something will actually be fetched: resolving the current
# release, the server bundle, or the console bundle. A fully local install (--archive, plus
# --console-archive when the console is wanted) needs no network at all - which is the point
# of those flags for air-gapped hosts.
NEEDS_NETWORK=0
[ -z "$VERSION" ] && [ -z "$ARCHIVE" ] && NEEDS_NETWORK=1
[ -z "$ARCHIVE" ] && NEEDS_NETWORK=1
[ "$WITH_CONSOLE" = 1 ] && [ -z "$CONSOLE_ARCHIVE" ] && NEEDS_NETWORK=1
if [ "$NEEDS_NETWORK" = 1 ]; then
  have curl || have wget \
    || die "need curl or wget to download the release (or pass --archive/--console-archive
       to install from local files)"
fi

# ---- version -----------------------------------------------------------------
fetch() {
  # fetch <url> <dest>
  if have curl; then curl -fsSL -o "$2" "$1"; else wget -qO "$2" "$1"; fi
}
fetch_stdout() {
  if have curl; then curl -fsSL "$1"; else wget -qO- "$1"; fi
}

if [ -n "$ARCHIVE" ]; then
  [ -f "$ARCHIVE" ] || die "--archive not found: $ARCHIVE"
  # frontcache-server-2.5.0.tar.gz -> 2.5.0
  if [ -z "$VERSION" ]; then
    # strip the extension, then a trailing platform classifier if the file is a runtime bundle
    VERSION="$(basename "$ARCHIVE" | sed -n 's/^frontcache-server-\(.*\)\.tar\.gz$/\1/p')"
    VERSION="$(printf '%s' "$VERSION" | sed -E 's/-(linux|macos)-(x64|aarch64)$//')"
    [ -n "$VERSION" ] || die "cannot infer the version from $(basename "$ARCHIVE") - pass --version"
  fi
  log "Installing version $VERSION from the local archive $ARCHIVE"
elif [ -z "$VERSION" ]; then
  log "Resolving the current release from the repository ..."
  VERSION="$(fetch_stdout "$MAVEN_BASE/frontcache-server/maven-metadata.xml" \
             | sed -n 's:.*<release>\(.*\)</release>.*:\1:p' | head -1)" || true
  [ -n "$VERSION" ] || die "could not resolve the current release - pass --version explicitly"
  log "Current release is $VERSION"
fi
[ -n "$VERSION" ] || die "no version resolved"

# --help documents the sed above: maven-metadata.xml is the repository's own record of the
# current release, so the installer needs no baked-in version and never goes stale.

TARGET_DIR="$INSTALL_DIR/frontcache-server-$VERSION"
LINK_DIR="$INSTALL_DIR/frontcache"
CONSOLE_TARGET_DIR="$INSTALL_DIR/frontcache-console-$VERSION"
CONSOLE_LINK_DIR="$INSTALL_DIR/frontcache-console"

# ---- platform (for --with-runtime) -------------------------------------------
# The runtime bundles are per-OS/arch because jlink cannot cross-link, so the installer has
# to name the right one. Detected rather than asked for: getting it wrong yields a bundle
# whose launcher cannot execute at all.
detect_runtime_target() {
  local os arch
  case "$(uname -s)" in
    Linux)  os=linux ;;
    Darwin) os=macos ;;
    *)      die "--with-runtime: no runtime bundle is published for $(uname -s)" ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64)  arch=x64 ;;
    aarch64|arm64) arch=aarch64 ;;
    *)             die "--with-runtime: no runtime bundle is published for $(uname -m)" ;;
  esac
  echo "$os-$arch"
}

if [ "$WITH_RUNTIME" = 1 ]; then
  RUNTIME_TARGET="$(detect_runtime_target)"
  log "--with-runtime: this host is $RUNTIME_TARGET; the bundle will carry its own Java runtime"
  SKIP_JDK=1
fi

# ---- JDK ---------------------------------------------------------------------
java_is_25() {
  local java_bin="${1:-java}"
  have "$java_bin" || return 1
  local major
  major="$("$java_bin" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
  [ -n "$major" ] && [ "$major" -ge 25 ]
}

install_jdk() {
  if [ "$WITH_RUNTIME" = 1 ]; then
    log "Skipping the JDK: the bundle brings its own runtime (the launcher prefers it over \$JAVA_HOME)"
    return 0
  fi
  if java_is_25; then
    log "A Java 25+ runtime is already present ($(java -version 2>&1 | head -1))"
    return 0
  fi
  if [ "$SKIP_JDK" = 1 ]; then
    die "--skip-jdk was given but no Java 25+ runtime is on PATH"
  fi

  if ! have apt-get; then
    die "no Java 25+ runtime found, and automatic JDK install is only implemented for
       apt-based systems. Install a JDK 25 yourself (https://adoptium.net/temurin/releases/)
       and re-run with --skip-jdk."
  fi

  log "Installing a JDK 25 ..."
  run_sh 'apt-get update -y'
  # Prefer the distro package - Ubuntu 24 (noble) does now carry openjdk-25-jdk
  # (25.0.3 as of 2026-08). Fall back to the Adoptium (Eclipse Temurin) apt repository for
  # images and mirrors where it is still absent.
  #
  # This probe is deliberately NOT wrapped in run_sh: it only reads the package index, and
  # stubbing it under --dry-run made the dry run report a branch it would not have taken.
  if apt-cache show openjdk-25-jdk > /dev/null 2>&1; then
    run_sh 'DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-25-jdk'
  else
    log "openjdk-25-jdk is not available here - adding the Adoptium repository"
    run_sh 'DEBIAN_FRONTEND=noninteractive apt-get install -y wget apt-transport-https gnupg ca-certificates'
    run_sh 'mkdir -p /etc/apt/keyrings'
    run_sh 'wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public > /etc/apt/keyrings/adoptium.asc'
    run_sh 'echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" > /etc/apt/sources.list.d/adoptium.list'
    run_sh 'apt-get update -y'
    run_sh 'DEBIAN_FRONTEND=noninteractive apt-get install -y temurin-25-jdk'
  fi
  [ "$DRY_RUN" = 1 ] || java_is_25 || die "JDK install finished but java -version still reports < 25"
}
install_jdk

# ---- service user ------------------------------------------------------------
if id "$SERVICE_USER" > /dev/null 2>&1; then
  log "Service user '$SERVICE_USER' already exists"
else
  log "Creating the '$SERVICE_USER' system user"
  # no login shell, no home: it only needs to own the install and run the launcher
  run useradd --system --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"
fi

# ---- download + verify -------------------------------------------------------
WORK_DIR=""
cleanup() { [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ] && rm -rf "$WORK_DIR"; }
trap cleanup EXIT

verify_sha256() {
  # verify_sha256 <file> <sha256-file>   -- fails closed
  local file="$1" shafile="$2" expected actual
  expected="$(awk '{print $1; exit}' "$shafile")"
  [ -n "$expected" ] || die "empty checksum file: $shafile"
  if have sha256sum; then
    actual="$(sha256sum "$file" | awk '{print $1}')"
  elif have shasum; then
    actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  else
    die "no sha256sum/shasum available to verify the download - refusing to continue"
  fi
  [ "$expected" = "$actual" ] \
    || die "checksum MISMATCH for $(basename "$file")
       expected $expected
       actual   $actual"
  log "Checksum OK: $(basename "$file")"
}

# stage_archive <module>  -> echoes the staged tarball path on stdout (nothing else)
stage_archive() {
  # NB two statements: bash's `local` expands every word argument before assigning any of
  # them, so `local a="$1" b="$a..."` sees an unset $a (fatal under set -u).
  local module="$1"
  local tgz="$module-$VERSION.tar.gz"
  local local_copy=""

  case "$module" in
    frontcache-server)  local_copy="$ARCHIVE" ;;
    frontcache-console) local_copy="$CONSOLE_ARCHIVE" ;;
  esac

  if [ -n "$local_copy" ]; then
    [ -f "$local_copy" ] || die "archive not found: $local_copy"
    cp "$local_copy" "$WORK_DIR/$tgz"
    if [ -f "$local_copy.sha256" ]; then
      cp "$local_copy.sha256" "$WORK_DIR/$tgz.sha256"
      verify_sha256 "$WORK_DIR/$tgz" "$WORK_DIR/$tgz.sha256"
    else
      warn "no $local_copy.sha256 beside the archive - installing it UNVERIFIED"
    fi
  else
    # --with-runtime swaps in the per-platform artifact. It unpacks to the SAME internal
    # directory name as the portable bundle, so nothing downstream changes - only `runtime/`
    # is extra.
    if [ -n "$RUNTIME_TARGET" ]; then
      tgz="$module-$VERSION-$RUNTIME_TARGET.tar.gz"
    fi
    local base="$MAVEN_BASE/$module/$VERSION"
    if [ "$DRY_RUN" = 1 ]; then
      # a dry run must not touch the network either
      log "[dry-run] would download and verify $base/$tgz (+ .sha256)"
      echo "$WORK_DIR/$tgz"
      return 0
    fi
    log "Downloading $tgz ..."
    fetch "$base/$tgz" "$WORK_DIR/$tgz" || die "download failed: $base/$tgz"
    fetch "$base/$tgz.sha256" "$WORK_DIR/$tgz.sha256" \
      || die "checksum file missing: $base/$tgz.sha256 (refusing to install unverified)"
    verify_sha256 "$WORK_DIR/$tgz" "$WORK_DIR/$tgz.sha256"
  fi
  echo "$WORK_DIR/$tgz"
}

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/frontcache-install.XXXXXX")"

# ---- config carry-over -------------------------------------------------------
# The rule: an existing config file is NEVER overwritten. New defaults land beside it as
# <file>.new so the operator can diff them in. Only files the old install did not have are
# taken from the new bundle as-is.
#
# legacy_conf_name is what keeps that rule true across a RENAMED config file. The loop below
# sees the old name as "a file this release dropped" and carries it over, which is right - but
# the operator's tuning is now under a name Frontcache does not read, so it is RENAMED into
# place and the bundle's shipped default is demoted to <file>.new.
#
# It used to be demoted the other way round: the carried-over legacy file stayed where it was and
# stayed authoritative, because Frontcache read the old name as a fallback. 2.9.0 removed that
# fallback, so leaving the file under its old name would hand the operator a node running every
# circuit breaker, timeout and bulkhead at its BUILT-IN default while their tuned file sat in the
# same directory being ignored. Renaming it here is the whole migration for them.
#
# One line per renamed config file. Drop a line once nobody could still be upgrading from a
# release that shipped the old name.
legacy_conf_name() {
  case "$1" in
    resilience.properties) echo hystrix.properties ;;   # renamed in 2.7, old name unread since 2.9.0
  esac
}

carry_over_conf() {
  local old_conf="$1" new_conf="$2" label="$3"
  local name added=0 differs=0 renamed=0 legacy

  if [ ! -d "$old_conf" ]; then
    log "No previous $label config to carry over - using the shipped defaults"
    return 0
  fi

  log "Carrying over the existing $label config from $old_conf"
  for old in "$old_conf"/*; do
    [ -f "$old" ] || continue
    name="$(basename "$old")"
    if [ -f "$new_conf/$name" ]; then
      if ! cmp -s "$old" "$new_conf/$name"; then
        run cp -a "$new_conf/$name" "$new_conf/$name.new"
        differs=$((differs + 1))
      fi
      run cp -a "$old" "$new_conf/$name"
    else
      # a file this release dropped; keep it rather than silently discarding config
      run cp -a "$old" "$new_conf/$name"
      added=$((added + 1))
    fi
  done

  # Renamed files: the operator's pre-rename file was carried over by the loop above, under a name
  # this release no longer reads. Move it onto the current name and demote the shipped default,
  # so their tuning is what actually takes effect.
  for new in "$new_conf"/*; do
    [ -f "$new" ] || continue
    name="$(basename "$new")"
    legacy="$(legacy_conf_name "$name")"
    [ -n "$legacy" ] || continue
    [ -f "$new_conf/$legacy" ] || continue
    [ -f "$old_conf/$legacy" ] || continue

    # Only when the operator has NOT already migrated. If both names exist in the old install,
    # the current one is the one they meant and the legacy file is a leftover - leave both alone.
    if [ -f "$old_conf/$name" ]; then
      log "    $new_conf/$legacy is a leftover from before the $legacy -> $name rename and is NOT"
      log "      read; your $name is in charge. Delete $legacy when convenient."
      continue
    fi

    run mv "$new_conf/$name" "$new_conf/$name.new"
    run mv "$new_conf/$legacy" "$new_conf/$name"
    log "    $legacy was renamed to $name and the old name is no longer read - your settings have"
    log "      been MOVED to $name so they still apply; the shipped default is beside them as"
    log "      $name.new. The hystrix.* keys inside the file are still supported, so nothing"
    log "      inside it needed to change."
    renamed=$((renamed + 1))
  done

  log "    your config is in place; $differs file(s) whose shipped default changed have a .new beside them"
  [ "$added" -gt 0 ] && log "    $added file(s) not in this release were kept"
  [ "$renamed" -gt 0 ] && log "    $renamed renamed file(s) had your settings moved onto the current name"
  if [ "$differs" -gt 0 ]; then
    log "    review them with:  for f in $new_conf/*.new; do diff -u \"\${f%.new}\" \"\$f\"; done"
  fi
  return 0
}

# ---- install the server ------------------------------------------------------
SERVER_TGZ="$(stage_archive frontcache-server)"

# Stop the service before touching a directory it is RUNNING FROM. This only applies to
# re-installing the SAME version: a new version unpacks into its own directory, the old
# process keeps serving from the old one, and the symlink flip plus restart at the end is the
# whole cutover. Without this, a same-version re-run would pull the tree out from under a
# live JVM.
stop_service_if_running() {
  local svc="$1"
  [ "$HAVE_SYSTEMD" = 1 ] || return 0
  if systemctl is-active --quiet "$svc" 2>/dev/null; then
    log "Stopping $svc before replacing its install directory"
    run systemctl stop "$svc"
  fi
}

log "Unpacking to $TARGET_DIR ..."
if [ -d "$TARGET_DIR" ]; then
  # re-running the same version: keep the config, replace the code
  log "$TARGET_DIR already exists - preserving its conf/ and replacing the rest"
  stop_service_if_running "$SERVICE_NAME"
  run_sh "rm -rf '$WORK_DIR/prev-conf' && mkdir -p '$WORK_DIR/prev-conf' \
          && cp -a '$TARGET_DIR/FRONTCACHE_HOME/conf/.' '$WORK_DIR/prev-conf/' 2>/dev/null || true"
  run rm -rf "$TARGET_DIR"
  PREV_CONF="$WORK_DIR/prev-conf"
elif [ -L "$LINK_DIR" ]; then
  PREV_CONF="$(readlink -f "$LINK_DIR")/FRONTCACHE_HOME/conf"
else
  PREV_CONF=""
fi

run_sh "tar -xzf '$SERVER_TGZ' -C '$INSTALL_DIR'"
[ "$DRY_RUN" = 1 ] || [ -d "$TARGET_DIR" ] || die "archive did not expand to $TARGET_DIR"

[ -n "$PREV_CONF" ] && carry_over_conf "$PREV_CONF" "$TARGET_DIR/FRONTCACHE_HOME/conf" "server"

# ---- server config -----------------------------------------------------------
PROPS="$TARGET_DIR/FRONTCACHE_HOME/conf/frontcache.properties"

set_prop() {
  # set_prop <key> <value> - replace in place, or append when absent
  local key="$1" value="$2" escaped
  escaped="$(printf '%s' "$key" | sed 's/\./\\./g')"
  if [ "$DRY_RUN" = 1 ]; then
    printf '    [dry-run] set %s=%s in %s\n' "$key" "$value" "$(basename "$PROPS")" >&2
    return 0
  fi
  if grep -qE "^[[:space:]]*${escaped}[[:space:]]*=" "$PROPS"; then
    sed -i -E "s|^[[:space:]]*${escaped}[[:space:]]*=.*|${key}=${value}|" "$PROPS"
  else
    printf '\n%s=%s\n' "$key" "$value" >> "$PROPS"
  fi
}

if [ -n "$ORIGIN_HOST" ]; then
  log "Setting the origin to $ORIGIN_HOST:$ORIGIN_PORT"
  set_prop 'front-cache.origin-host' "$ORIGIN_HOST"
  set_prop 'front-cache.origin-https-port' "$ORIGIN_PORT"
else
  log "No --origin-host given - leaving front-cache.origin-host as it is in the config"
  ORIGIN_HOST="$(sed -n -E 's|^[[:space:]]*front-cache\.origin-host[[:space:]]*=[[:space:]]*([^[:space:]]+).*|\1|p' "$PROPS" 2>/dev/null | tail -1)" || true
fi

# front-cache.http-port / https-port are what redirect rewriting uses, so they must be the
# CLIENT-FACING ports - what a browser sees, not what Frontcache binds. With nothing in front,
# that is $FC_PORT. Put a front door on 80/443 in front of it and these have to change to
# 80/443, or redirects will send users to $FC_PORT (examples/front-door does this for you).
set_prop 'front-cache.http-port' "$FC_PORT"

run chown -R "$SERVICE_USER:$SERVICE_USER" "$TARGET_DIR"
run ln -sfn "$TARGET_DIR" "$LINK_DIR"

# ---- systemd unit ------------------------------------------------------------
write_unit() {
  local name="$1" desc="$2" workdir="$3" execstart="$4" extra_env="$5"
  local unit="/etc/systemd/system/${name}.service"
  log "Writing $unit"
  if [ "$DRY_RUN" = 1 ]; then
    printf '    [dry-run] write the %s unit\n' "$name" >&2
    return 0
  fi
  cat > "$unit" <<UNIT
[Unit]
Description=$desc
After=network.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_USER
WorkingDirectory=$workdir
$extra_env
ExecStart=$execstart
# 143 = SIGTERM; a clean stop must not be reported as a failure
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT
}

write_unit "$SERVICE_NAME" "Frontcache standalone server" \
  "$LINK_DIR/server/bin" "$LINK_DIR/server/bin/frontcache" \
  "Environment=FRONTCACHE_HTTP_PORT=$FC_PORT"

# ---- console (optional) ------------------------------------------------------
if [ "$WITH_CONSOLE" = 1 ]; then
  CONSOLE_TGZ="$(stage_archive frontcache-console)"

  if [ -d "$CONSOLE_TARGET_DIR" ]; then
    stop_service_if_running "$CONSOLE_SERVICE_NAME"
    run_sh "rm -rf '$WORK_DIR/prev-console-conf' && mkdir -p '$WORK_DIR/prev-console-conf' \
            && cp -a '$CONSOLE_TARGET_DIR/conf/.' '$WORK_DIR/prev-console-conf/' 2>/dev/null || true"
    run rm -rf "$CONSOLE_TARGET_DIR"
    PREV_CONSOLE_CONF="$WORK_DIR/prev-console-conf"
  elif [ -L "$CONSOLE_LINK_DIR" ]; then
    PREV_CONSOLE_CONF="$(readlink -f "$CONSOLE_LINK_DIR")/conf"
  else
    PREV_CONSOLE_CONF=""
  fi

  log "Unpacking the console to $CONSOLE_TARGET_DIR ..."
  run_sh "tar -xzf '$CONSOLE_TGZ' -C '$INSTALL_DIR'"
  [ -n "$PREV_CONSOLE_CONF" ] && carry_over_conf "$PREV_CONSOLE_CONF" "$CONSOLE_TARGET_DIR/conf" "console"

  run chown -R "$SERVICE_USER:$SERVICE_USER" "$CONSOLE_TARGET_DIR"
  run ln -sfn "$CONSOLE_TARGET_DIR" "$CONSOLE_LINK_DIR"

  write_unit "$CONSOLE_SERVICE_NAME" "Frontcache console" \
    "$CONSOLE_LINK_DIR/bin" "$CONSOLE_LINK_DIR/bin/frontcache-console" \
    "Environment=FRONTCACHE_CONSOLE_PORT=7080"
fi

# ---- start -------------------------------------------------------------------
if [ "$HAVE_SYSTEMD" = 1 ]; then
  run systemctl daemon-reload
  run systemctl enable "$SERVICE_NAME"
  run systemctl restart "$SERVICE_NAME"
  [ "$WITH_CONSOLE" = 1 ] && { run systemctl enable "$CONSOLE_SERVICE_NAME"; run systemctl restart "$CONSOLE_SERVICE_NAME"; }
else
  warn "systemd is not available here, so the units were written but nothing was started."
  warn "Start it by hand with:"
  warn "  runuser -u $SERVICE_USER -- env FRONTCACHE_HTTP_PORT=$FC_PORT $LINK_DIR/server/bin/frontcache"
fi

# ---- report ------------------------------------------------------------------
cat <<REPORT

>>> Frontcache $VERSION installed.

    install    $TARGET_DIR
    active     $LINK_DIR -> $TARGET_DIR
    config     $LINK_DIR/FRONTCACHE_HOME/conf
    logs       $LINK_DIR/FRONTCACHE_HOME/logs
    listening  http://127.0.0.1:$FC_PORT  (plain HTTP - put your front door in front of it)
    java       $([ "$WITH_RUNTIME" = 1 ] && echo "$LINK_DIR/runtime (bundled - no host JDK involved)" || echo "the host's JDK")

    Verify:
      systemctl status $SERVICE_NAME
      journalctl -u $SERVICE_NAME -f
      curl -s -o /dev/null -w '%{http_code}\\n' "http://127.0.0.1:$FC_PORT/frontcache-io?action=get-cache-state"
        # 200 means Frontcache is live (the body says "access denied" unless the request
        # carries front-cache.api-key - that is expected, and is why this works as a
        # liveness check without an API key)
$([ "$WITH_CONSOLE" = 1 ] && printf '      systemctl status %s        # console on http://127.0.0.1:7080/\n' "$CONSOLE_SERVICE_NAME")
    Roll back to a previously installed version:
      ln -sfn $INSTALL_DIR/frontcache-server-<older> $LINK_DIR && systemctl restart $SERVICE_NAME

    Frontcache terminates no TLS. Put a front door on 80/443 in front of it and set
    front-cache.http-port / front-cache.https-port to the ports CLIENTS see - see
    examples/front-door in the public repo for a worked nginx one.

    Before serving real traffic, review $LINK_DIR/FRONTCACHE_HOME/conf/frontcache.properties -
    at minimum front-cache.api-key and front-cache.default-domain, and give this node a
    distinct front-cache.id in conf/frontcache.id.
REPORT
