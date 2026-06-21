#!/usr/bin/env bash
#
# Install standalone Frontcache on a remote Ubuntu 24 server.
#
# - installs openjdk-11
# - creates ~/opt on the remote server
# - copies the local frontcache-server directory there
# - runs Frontcache as a systemd service on 9080 (HTTP) / 9443 (HTTPS)
# - installs nginx as the public front door on 80/443:
#     * /                          -> local Frontcache (80->9080, 443->9443)
#     * /images/ /fs/ /o9r/ /pi/   -> origin host ($ORIGIN_HOST)
#
# Example target:
#   ec2-54-208-212-231.compute-1.amazonaws.com  (Ubuntu 24)
#   ssh -i ~/.ssh/coins-2023.pem ubuntu@ec2-54-208-212-231.compute-1.amazonaws.com
#
set -euo pipefail

# ---- configuration -----------------------------------------------------------
REMOTE_HOST="${REMOTE_HOST:-ec2-123-456-789-123.compute-1.amazonaws.com}"
REMOTE_USER="${REMOTE_USER:-ubuntu}"
PEM_FILE="${PEM_FILE:-$HOME/.ssh/your-keys.pem}"

# directory created on the remote server
REMOTE_DIR="opt"   # relative to the remote user's home (~/opt)

# systemd service name
SERVICE_NAME="frontcache"

# nginx reverse-proxy settings
ORIGIN_HOST="${ORIGIN_HOST:-direct.hobbyray.com}"   # backend for the origin-served paths
ORIGIN_PATHS="${ORIGIN_PATHS:-/o9r/ /fs/ /pi/ /st/ /images/ /css/ /js/ /page-cache/}"  # space-separated; proxied to $ORIGIN_HOST
FRONTCACHE_HTTP_PORT="${FRONTCACHE_HTTP_PORT:-9080}"  # local Frontcache HTTP port
FRONTCACHE_HTTPS_PORT="${FRONTCACHE_HTTPS_PORT:-9443}" # local Frontcache HTTPS port

# local frontcache-server directory to copy
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_SERVER_DIR="$SCRIPT_DIR/frontcache-server"
# ------------------------------------------------------------------------------

SSH_TARGET="$REMOTE_USER@$REMOTE_HOST"
SSH_OPTS=(-i "$PEM_FILE" -o StrictHostKeyChecking=accept-new)

if [ ! -f "$PEM_FILE" ]; then
  echo "ERROR: pem file not found: $PEM_FILE" >&2
  exit 1
fi

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
( cd "$SCRIPT_DIR" && ./gradlew :frontcache-server:build )

if [ ! -f "$ROOT_WAR" ]; then
  echo "ERROR: build did not produce $ROOT_WAR" >&2
  exit 1
fi
echo ">>> WAR is fresh: $ROOT_WAR"
# ------------------------------------------------------------------------------

echo ">>> Installing openjdk-11 on $SSH_TARGET ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" '
  set -e
  sudo apt-get update -y
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-11-jdk
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
LOCAL_ARCHIVE="$SCRIPT_DIR/$ARCHIVE"
# COPYFILE_DISABLE stops macOS bsdtar from emitting AppleDouble (._*) sidecars;
# --exclude drops any that already exist on disk plus .DS_Store. Jetty globs
# start.d/*.ini and would try to parse a stray ._http.ini as UTF-8 config and crash.
COPYFILE_DISABLE=1 tar --exclude='._*' --exclude='.DS_Store' \
  -czf "$LOCAL_ARCHIVE" -C "$SCRIPT_DIR" "$(basename "$LOCAL_SERVER_DIR")"

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

echo ">>> Installing and starting systemd service '$SERVICE_NAME' on $SSH_TARGET ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "
  set -e
  REMOTE_HOME=\$(eval echo ~$REMOTE_USER)
  SERVER_DIR=\"\$REMOTE_HOME/$REMOTE_DIR/frontcache-server\"
  chmod +x \"\$SERVER_DIR/server/bin/frontcache\" \"\$SERVER_DIR/bin/frontcache\"

  sudo tee /etc/systemd/system/$SERVICE_NAME.service > /dev/null <<EOF
[Unit]
Description=Frontcache standalone server
After=network.target

[Service]
Type=simple
# Frontcache listens on unprivileged 9080/9443; nginx fronts 80/443 (no CAP_NET_BIND_SERVICE needed)
User=$REMOTE_USER
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

# ---- nginx front door (80/443) ----------------------------------------------
# delegated to a dedicated script (also runnable standalone). It reads the same
# env-overridable config, exported here so its defaults are overridden by ours.
echo ">>> Configuring nginx front door via configure-nginx-remote.sh ..."
REMOTE_HOST="$REMOTE_HOST" REMOTE_USER="$REMOTE_USER" PEM_FILE="$PEM_FILE" \
REMOTE_DIR="$REMOTE_DIR" ORIGIN_HOST="$ORIGIN_HOST" ORIGIN_PATHS="$ORIGIN_PATHS" \
FRONTCACHE_HTTP_PORT="$FRONTCACHE_HTTP_PORT" FRONTCACHE_HTTPS_PORT="$FRONTCACHE_HTTPS_PORT" \
  "$SCRIPT_DIR/configure-nginx-remote.sh"

echo ">>> Done."
echo "    Frontcache server copied to ~/$REMOTE_DIR/frontcache-server on $REMOTE_HOST"
echo "    Frontcache (systemd '$SERVICE_NAME') on :$FRONTCACHE_HTTP_PORT / :$FRONTCACHE_HTTPS_PORT"
echo "    nginx front door on :80 / :443  ->  / = Frontcache, $ORIGIN_PATHS = $ORIGIN_HOST"
echo "    Useful commands on the server:"
echo "      sudo systemctl status $SERVICE_NAME    # frontcache"
echo "      sudo journalctl -u $SERVICE_NAME -f"
echo "      sudo systemctl status nginx            # front door"
echo "      sudo nginx -t && sudo systemctl reload nginx"
