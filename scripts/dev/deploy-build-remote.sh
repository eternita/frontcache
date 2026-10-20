#!/usr/bin/env bash
#
# DEVELOPER TOOL - push a build from THIS working tree to a remote Ubuntu 24 server.
#
# This is not the customer install path. It needs a repo checkout, a JDK, a Gradle run and ssh
# keys on the operator's laptop, and it ships whatever is in the working tree - which is
# exactly what you want for testing a change on a real host, and exactly what you do not want
# for an installation someone else has to reproduce.
#
# Customers (and any reproducible install) use scripts/dist/install-frontcache.sh instead: it
# runs on the target host, downloads a published release, verifies its checksum, and preserves
# an existing config across upgrades.
#
# What this does:
#
# - installs openjdk-25
# - creates ~/opt on the remote server
# - copies the local frontcache-server directory there
# - runs Frontcache as a systemd service on 9080 (HTTP; embedded Jetty 12 is HTTP-only)
#
# It does NOT configure a front door. nginx on 80/443 is configured once per host, not once
# per deploy, and it lives with the rest of the front-door material:
# examples/front-door/vm/configure-nginx-remote.sh. The command is printed at the end.
#
# Flags:
#   --war-jar <file>  ALSO add <file> to the deployed war's WEB-INF/lib (repeatable) - e.g. a
#                     module found by ServiceLoader (org.frontcache.module.FrontcacheModule), which
#                     has to sit beside frontcache-core. Only the deployed copy changes; the
#                     checkout's own ROOT.war stays as built.
#
# Example (user + identity file come from your ~/.ssh/config; Ubuntu 24):
#   REMOTE_HOST=ec2-123-456-789-123.compute-1.amazonaws.com ./deploy-build-remote.sh
#
set -euo pipefail

# ---- configuration -----------------------------------------------------------
# REMOTE_HOST is an ssh alias/hostname resolved via ~/.ssh/config (User + IdentityFile there).
REMOTE_HOST="${REMOTE_HOST:-ec2-123-456-789-123.compute-1.amazonaws.com}"

# directory created on the remote server
REMOTE_DIR="opt"   # relative to the remote user's home (~/opt)

# systemd service name
SERVICE_NAME="frontcache"

ORIGIN_HOST="${ORIGIN_HOST:-direct.example.com}"      # backend, for the front-door hint below
FRONTCACHE_HTTP_PORT="${FRONTCACHE_HTTP_PORT:-9080}"  # local Frontcache HTTP port (Jetty 12 launcher is HTTP-only)

# local frontcache-server directory to copy
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"   # this script lives in scripts/dev
LOCAL_SERVER_DIR="$REPO_ROOT/frontcache-server"
# ------------------------------------------------------------------------------

# ---- args
WAR_JARS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --war-jar)
      [ $# -ge 2 ] || { echo "ERROR: --war-jar needs a file" >&2; exit 1; }
      [ -f "$2" ] || { echo "ERROR: --war-jar: no such file: $2" >&2; exit 1; }
      WAR_JARS+=("$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"); shift 2 ;;
    -h|--help) grep -E '^#( |$)' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "ERROR: unknown argument: $1 (use --war-jar <file>, or --help)" >&2; exit 1 ;;
  esac
done

SSH_TARGET="$REMOTE_HOST"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new)

if [ ! -d "$LOCAL_SERVER_DIR" ]; then
  echo "ERROR: local frontcache-server directory not found: $LOCAL_SERVER_DIR" >&2
  exit 1
fi

# ---- always build a fresh WAR so we never ship a stale artifact --------------
# The installer ships $LOCAL_SERVER_DIR/.../ROOT.war. Always rebuild it first so
# source changes reach the server. Done before any remote change, so a build
# failure aborts without stopping the live service.
ROOT_WAR="$LOCAL_SERVER_DIR/server/frontcache-base/webapps/ROOT.war"

echo ">>> Building fresh WAR (./gradlew :frontcache-server:build) ..."
( cd "$REPO_ROOT" && ./gradlew :frontcache-server:build )

if [ ! -f "$ROOT_WAR" ]; then
  echo "ERROR: build did not produce $ROOT_WAR" >&2
  exit 1
fi
echo ">>> WAR is fresh: $ROOT_WAR"

# --war-jar: a copy of the war with the extra jars in WEB-INF/lib, uploaded over the shipped one
# after extraction. zip copies every existing entry of the war as it is.
DEPLOY_WAR=""
if [ ${#WAR_JARS[@]} -gt 0 ]; then
  command -v zip > /dev/null 2>&1 || { echo "ERROR: --war-jar needs zip" >&2; exit 1; }
  WAR_WORK="$(mktemp -d "${TMPDIR:-/tmp}/fc-war.XXXXXX")"
  trap 'rm -rf "$WAR_WORK"' EXIT
  DEPLOY_WAR="$WAR_WORK/ROOT.war"
  cp "$ROOT_WAR" "$DEPLOY_WAR"
  mkdir -p "$WAR_WORK/stage/WEB-INF/lib"
  for j in "${WAR_JARS[@]}"; do
    name="$(basename "$j")"
    if unzip -l "$DEPLOY_WAR" "WEB-INF/lib/$name" > /dev/null 2>&1; then
      echo "ERROR: the war already has WEB-INF/lib/$name" >&2; exit 1
    fi
    cp "$j" "$WAR_WORK/stage/WEB-INF/lib/$name"
    echo "    + WEB-INF/lib/$name"
  done
  ( cd "$WAR_WORK/stage" && zip -q -X "$DEPLOY_WAR" WEB-INF/lib/*.jar )
fi
# ------------------------------------------------------------------------------

echo ">>> Installing openjdk-25 on $SSH_TARGET ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" '
  set -e
  sudo apt-get update -y
  # Prefer the distro package; Ubuntu 24 (noble) does now carry openjdk-25-jdk. Fall back to
  # the Adoptium (Eclipse Temurin) apt repo for images/mirrors where it is still absent.
  if ! apt-cache show openjdk-25-jdk >/dev/null 2>&1; then
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y wget apt-transport-https gnupg
    sudo mkdir -p /etc/apt/keyrings
    wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
      | sudo tee /etc/apt/keyrings/adoptium.asc > /dev/null
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" \
      | sudo tee /etc/apt/sources.list.d/adoptium.list > /dev/null
    sudo apt-get update -y
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y temurin-25-jdk
  else
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-25-jdk
  fi
  java -version
'

echo ">>> Cleaning up previous install on $SSH_TARGET ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "
  set -e
  # stop the service if it exists
  if systemctl list-unit-files | grep -q '^$SERVICE_NAME.service'; then
    sudo systemctl stop $SERVICE_NAME || true
  fi
  # delete the target folder
  rm -rf ~/$REMOTE_DIR/frontcache-server
"

echo ">>> Creating ~/$REMOTE_DIR on $SSH_TARGET ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "mkdir -p ~/$REMOTE_DIR"

echo ">>> Compressing frontcache-server ..."
ARCHIVE="frontcache-server.tar.gz"
LOCAL_ARCHIVE="$REPO_ROOT/$ARCHIVE"
# COPYFILE_DISABLE stops macOS bsdtar from emitting AppleDouble (._*) sidecars;
# --exclude drops any that already exist on disk plus .DS_Store. Jetty globs
# start.d/*.ini and would try to parse a stray ._http.ini as UTF-8 config and crash.
COPYFILE_DISABLE=1 tar --exclude='._*' --exclude='.DS_Store' \
  -czf "$LOCAL_ARCHIVE" -C "$REPO_ROOT" "$(basename "$LOCAL_SERVER_DIR")"

echo ">>> Uploading $ARCHIVE to $SSH_TARGET:~/$REMOTE_DIR/ ..."
scp "${SSH_OPTS[@]}" "$LOCAL_ARCHIVE" "$SSH_TARGET:~/$REMOTE_DIR/"

echo ">>> Extracting $ARCHIVE on $SSH_TARGET ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "
  set -e
  cd ~/$REMOTE_DIR
  tar -xzf $ARCHIVE
  rm -f $ARCHIVE
"

echo ">>> Removing local $ARCHIVE ..."
rm -f "$LOCAL_ARCHIVE"

if [ -n "$DEPLOY_WAR" ]; then
  echo ">>> Uploading the war with ${#WAR_JARS[@]} extra jar(s) to $SSH_TARGET ..."
  scp "${SSH_OPTS[@]}" "$DEPLOY_WAR" "$SSH_TARGET:$REMOTE_DIR/frontcache-server/server/frontcache-base/webapps/ROOT.war"
fi

echo ">>> Installing and starting systemd service '$SERVICE_NAME' on $SSH_TARGET ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "
  set -e
  REMOTE_USER=\$(whoami)
  SERVER_DIR=\"\$HOME/$REMOTE_DIR/frontcache-server\"
  chmod +x \"\$SERVER_DIR/server/bin/frontcache\" \"\$SERVER_DIR/bin/frontcache\"

  sudo tee /etc/systemd/system/$SERVICE_NAME.service > /dev/null <<EOF
[Unit]
Description=Frontcache standalone server
After=network.target

[Service]
Type=simple
# Frontcache listens on unprivileged 9080; the front door owns 80/443 (no CAP_NET_BIND_SERVICE needed)
User=\$REMOTE_USER
WorkingDirectory=\$SERVER_DIR/server/bin
ExecStart=\$SERVER_DIR/server/bin/frontcache
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

  sudo systemctl daemon-reload
  sudo systemctl enable $SERVICE_NAME
  sudo systemctl restart $SERVICE_NAME
  sleep 3
  sudo systemctl --no-pager status $SERVICE_NAME || true
"

echo ">>> Done."
echo "    Frontcache server copied to ~/$REMOTE_DIR/frontcache-server on $REMOTE_HOST"
echo "    Frontcache (systemd '$SERVICE_NAME') on :$FRONTCACHE_HTTP_PORT - plain HTTP, no TLS"
echo "    Useful commands on the server:"
echo "      sudo systemctl status $SERVICE_NAME"
echo "      sudo journalctl -u $SERVICE_NAME -f"
echo
echo "    No front door was configured - that is a once-per-host step, not a per-deploy one."
echo "    From this checkout:"
echo "      cd $REPO_ROOT/examples/front-door/vm"
echo "      REMOTE_HOST=$REMOTE_HOST ./configure-nginx-remote.sh --origin-host $ORIGIN_HOST"
