#!/usr/bin/env bash
#
# Install standalone Frontcache on a remote Ubuntu 24 server.
#
# - installs openjdk-11
# - creates ~/opt on the remote server
# - copies the local frontcache-server directory there
#
# Example target:
#   ec2-54-208-212-231.compute-1.amazonaws.com  (Ubuntu 24)
#   ssh -i ~/.ssh/coins-2023.pem ubuntu@ec2-54-208-212-231.compute-1.amazonaws.com
#
set -euo pipefail

# ---- configuration -----------------------------------------------------------
REMOTE_HOST="${REMOTE_HOST:-ec2-54-208-212-231.compute-1.amazonaws.com}"
REMOTE_USER="${REMOTE_USER:-ubuntu}"
PEM_FILE="${PEM_FILE:-$HOME/.ssh/coins-2023.pem}"

# directory created on the remote server
REMOTE_DIR="opt"   # relative to the remote user's home (~/opt)

# systemd service name
SERVICE_NAME="frontcache"

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

echo ">>> Installing openjdk-11 on $SSH_TARGET ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" '
  set -e
  sudo apt-get update -y
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-11-jdk
  java -version
'

echo ">>> Creating ~/$REMOTE_DIR on $SSH_TARGET ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "mkdir -p ~/$REMOTE_DIR"

echo ">>> Copying frontcache-server to $SSH_TARGET:~/$REMOTE_DIR/ ..."
scp "${SSH_OPTS[@]}" -r "$LOCAL_SERVER_DIR" "$SSH_TARGET:~/$REMOTE_DIR/"

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

echo ">>> Done."
echo "    Frontcache server copied to ~/$REMOTE_DIR/frontcache-server on $REMOTE_HOST"
echo "    Running as systemd service '$SERVICE_NAME'. Useful commands on the server:"
echo "      sudo systemctl status $SERVICE_NAME"
echo "      sudo systemctl restart $SERVICE_NAME"
echo "      sudo journalctl -u $SERVICE_NAME -f"
