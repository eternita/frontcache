#!/usr/bin/env bash
#
# Configure nginx as the public front door for a remote Frontcache host.
#
# Installs nginx (if missing), extracts the Jetty keystore into PEM for TLS, and
# writes the default site so that:
#     /                          -> local Frontcache (80->$FRONTCACHE_HTTP_PORT, 443->$FRONTCACHE_HTTPS_PORT)
#     /images/ /fs/ /o9r/ /pi/   -> origin host ($ORIGIN_HOST, https)
#
# Can be run standalone or called from install-frontcache-server-remote.sh
# (which exports the same env vars). All settings are env-overridable.
#
# Standalone example:
#   REMOTE_HOST=my-host ORIGIN_HOST=origin.example.com ./configure-nginx-remote.sh
#
set -euo pipefail

# ---- configuration (env-overridable; defaults match install-frontcache-server-remote.sh)
REMOTE_HOST="${REMOTE_HOST:-ec2-54-208-212-231.compute-1.amazonaws.com}"
REMOTE_USER="${REMOTE_USER:-ubuntu}"
PEM_FILE="${PEM_FILE:-$HOME/.ssh/coins-2023.pem}"
REMOTE_DIR="${REMOTE_DIR:-opt}"                       # ~/opt/frontcache-server on the remote

ORIGIN_HOST="${ORIGIN_HOST:-origin.hobbyray.com}"     # backend for the origin-served paths
ORIGIN_PATHS="${ORIGIN_PATHS:-/images/ /fs/ /o9r/ /pi/}"  # space-separated; proxied to $ORIGIN_HOST
FRONTCACHE_HTTP_PORT="${FRONTCACHE_HTTP_PORT:-9080}"  # local Frontcache HTTP port
FRONTCACHE_HTTPS_PORT="${FRONTCACHE_HTTPS_PORT:-9443}" # local Frontcache HTTPS port

# Jetty keystore credentials (defaults are the standard Jetty demo keystore values;
# deobfuscated from start.d/ssl.ini: keyStorePassword=storepwd, keyManagerPassword=keypwd)
KEYSTORE_STOREPASS="${KEYSTORE_STOREPASS:-storepwd}"
KEYSTORE_KEYPASS="${KEYSTORE_KEYPASS:-keypwd}"
KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-jetty}"
# ------------------------------------------------------------------------------

SSH_TARGET="$REMOTE_USER@$REMOTE_HOST"
SSH_OPTS=(-i "$PEM_FILE" -o StrictHostKeyChecking=accept-new)

if [ ! -f "$PEM_FILE" ]; then
  echo "ERROR: pem file not found: $PEM_FILE" >&2
  exit 1
fi

echo ">>> Generating nginx site config (origin: $ORIGIN_HOST) ..."

# common proxy headers — single-quoted so nginx runtime vars ($remote_addr, ...) stay LITERAL
PROXY_HDRS='        proxy_http_version 1.1;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;'

# origin location blocks, one per configured path -> $ORIGIN_HOST (https)
ORIGIN_LOCATIONS=""
for p in $ORIGIN_PATHS; do
  ORIGIN_LOCATIONS+="    location ${p} {
${PROXY_HDRS}
        proxy_set_header Host ${ORIGIN_HOST};
        proxy_ssl_server_name on;
        proxy_pass https://${ORIGIN_HOST};
    }

"
done

# Build the site config locally. Unquoted heredoc: our shell vars (ports, origin,
# ${ORIGIN_LOCATIONS}, ${PROXY_HDRS}) expand; nginx vars are escaped as \$.
NGINX_CONF="$(mktemp)"
cat > "$NGINX_CONF" <<EOF
# Managed by configure-nginx-remote.sh — do not edit by hand.
# /                        -> local Frontcache (80->${FRONTCACHE_HTTP_PORT}, 443->${FRONTCACHE_HTTPS_PORT})
# ${ORIGIN_PATHS} -> ${ORIGIN_HOST}

server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;

${ORIGIN_LOCATIONS}    # everything else -> local Frontcache (HTTP)
    location / {
${PROXY_HDRS}
        proxy_set_header Host \$host;
        proxy_pass http://127.0.0.1:${FRONTCACHE_HTTP_PORT};
    }
}

server {
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    server_name _;

    ssl_certificate     /etc/nginx/ssl/frontcache.crt;
    ssl_certificate_key /etc/nginx/ssl/frontcache.key;

${ORIGIN_LOCATIONS}    # everything else -> local Frontcache (HTTPS)
    location / {
${PROXY_HDRS}
        proxy_set_header Host \$host;
        proxy_ssl_verify off;
        proxy_pass https://127.0.0.1:${FRONTCACHE_HTTPS_PORT};
    }
}
EOF

echo ">>> Uploading nginx config to $SSH_TARGET ..."
scp "${SSH_OPTS[@]}" "$NGINX_CONF" "$SSH_TARGET:/tmp/frontcache-nginx.conf"
rm -f "$NGINX_CONF"

echo ">>> Installing & configuring nginx on $SSH_TARGET ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "
  set -e
  # install nginx if not already present
  if ! command -v nginx >/dev/null 2>&1; then
    echo 'nginx not found - installing ...'
    sudo apt-get update -y
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y nginx
  else
    echo 'nginx already installed'
  fi

  # extract the Jetty keystore (alias '$KEYSTORE_ALIAS') into PEM for nginx TLS.
  # store pass and key pass differ in the demo keystore, so pass both.
  REMOTE_HOME=\$(eval echo ~$REMOTE_USER)
  KEYSTORE=\"\$REMOTE_HOME/$REMOTE_DIR/frontcache-server/server/frontcache-base/etc/keystore\"
  TMP=\$(mktemp -d)
  keytool -importkeystore -noprompt \
    -srckeystore \"\$KEYSTORE\" -srcstorepass $KEYSTORE_STOREPASS -srckeypass $KEYSTORE_KEYPASS -srcalias $KEYSTORE_ALIAS \
    -destkeystore \"\$TMP/fc.p12\" -deststoretype PKCS12 -deststorepass $KEYSTORE_STOREPASS
  sudo mkdir -p /etc/nginx/ssl
  sudo openssl pkcs12 -in \"\$TMP/fc.p12\" -passin pass:$KEYSTORE_STOREPASS -nokeys  -out /etc/nginx/ssl/frontcache.crt
  sudo openssl pkcs12 -in \"\$TMP/fc.p12\" -passin pass:$KEYSTORE_STOREPASS -nodes -nocerts -out /etc/nginx/ssl/frontcache.key
  sudo chmod 600 /etc/nginx/ssl/frontcache.key
  rm -rf \"\$TMP\"

  # install the generated site as nginx's default site
  sudo mv /tmp/frontcache-nginx.conf /etc/nginx/sites-available/default
  sudo ln -sf /etc/nginx/sites-available/default /etc/nginx/sites-enabled/default
  sudo nginx -t
  sudo systemctl enable nginx
  sudo systemctl restart nginx
  sudo systemctl --no-pager status nginx | head -5 || true
"

echo ">>> nginx configured: :80/:443 -> / = Frontcache, $ORIGIN_PATHS = $ORIGIN_HOST"
