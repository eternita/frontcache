#!/usr/bin/env bash
#
# fc-server container entrypoint.
#
# Runs the same two processes the AWS single-host install runs, but in one container:
#   1. Frontcache standalone (embedded Jetty 12, HTTP only) on $FRONTCACHE_HTTP_PORT
#   2. nginx front door on 80/443 (terminates TLS, proxies / -> Frontcache,
#      $ORIGIN_PATHS -> $ORIGIN_HOST)
#
# The nginx site config and the TLS cert are generated here at startup from env vars,
# mirroring scripts/aws/configure-nginx-remote.sh, so a single image serves any deployment.
set -euo pipefail

# ---- configuration (env-overridable) ----------------------------------------
SERVER_DIR="/opt/frontcache-server"
FRONTCACHE_HTTP_PORT="${FRONTCACHE_HTTP_PORT:-9080}"

ORIGIN_HOST="${ORIGIN_HOST:-origin.example.com}"           # backend for origin-served paths
ORIGIN_PATHS="${ORIGIN_PATHS:-/o9r/ /fs/ /pi/ /st/ /images/ /css/ /js/ /page-cache/}"

# Jetty demo keystore credentials (same defaults as configure-nginx-remote.sh)
KEYSTORE="${KEYSTORE:-$SERVER_DIR/server/frontcache-base/etc/keystore}"
KEYSTORE_STOREPASS="${KEYSTORE_STOREPASS:-storepwd}"
KEYSTORE_KEYPASS="${KEYSTORE_KEYPASS:-keypwd}"
KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-jetty}"
# -----------------------------------------------------------------------------

# ---- TLS: extract the Jetty keystore into PEM for nginx ----------------------
echo ">>> Extracting TLS cert from Jetty keystore ..."
mkdir -p /etc/nginx/ssl
TMP="$(mktemp -d)"
keytool -importkeystore -noprompt \
  -srckeystore "$KEYSTORE" -srcstorepass "$KEYSTORE_STOREPASS" -srckeypass "$KEYSTORE_KEYPASS" -srcalias "$KEYSTORE_ALIAS" \
  -destkeystore "$TMP/fc.p12" -deststoretype PKCS12 -deststorepass "$KEYSTORE_STOREPASS"
openssl pkcs12 -in "$TMP/fc.p12" -passin pass:"$KEYSTORE_STOREPASS" -nokeys  -out /etc/nginx/ssl/frontcache.crt
openssl pkcs12 -in "$TMP/fc.p12" -passin pass:"$KEYSTORE_STOREPASS" -nodes -nocerts -out /etc/nginx/ssl/frontcache.key
chmod 600 /etc/nginx/ssl/frontcache.key
rm -rf "$TMP"

# ---- nginx site config (rendered from env) -----------------------------------
# Common proxy headers — single-quoted so nginx runtime vars stay literal.
PROXY_HDRS='        proxy_http_version 1.1;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Port $server_port;'

# Frontcache serves plaintext (can't emit brotli), so nginx gzips proxied responses.
GZIP_CONF='    gzip on;
    gzip_vary on;
    gzip_proxied any;
    gzip_comp_level 5;
    gzip_min_length 256;
    gzip_http_version 1.1;
    gzip_types text/plain text/css text/xml text/javascript application/json application/javascript application/xml application/rss+xml image/svg+xml;'

# One location block per origin path -> $ORIGIN_HOST (https).
# The origin host is assigned to an nginx variable so it is resolved at request
# time via the Docker embedded DNS (resolver, below) rather than at config-load
# time. That keeps `nginx -t` and startup working even if the origin is briefly
# unresolvable, so an origin DNS blip never takes down cache serving on `/`.
ORIGIN_LOCATIONS=""
for p in $ORIGIN_PATHS; do
  ORIGIN_LOCATIONS+="    location ${p} {
${PROXY_HDRS}
        proxy_set_header Host ${ORIGIN_HOST};
        proxy_ssl_server_name on;
        proxy_ssl_name ${ORIGIN_HOST};
        set \$fc_origin \"${ORIGIN_HOST}\";
        proxy_pass https://\$fc_origin;
    }

"
done

# Docker's embedded DNS resolver; used for the variable-based origin proxy_pass above.
RESOLVER='    resolver 127.0.0.11 ipv6=off valid=30s;'

echo ">>> Rendering nginx site config (origin: $ORIGIN_HOST) ..."
# Unquoted heredoc: our shell vars expand; nginx runtime vars are escaped as \$.
cat > /etc/nginx/sites-available/default <<EOF
# Managed by fc-server entrypoint.sh — do not edit by hand.
# /                        -> local Frontcache (80 and TLS-terminated 443 -> http ${FRONTCACHE_HTTP_PORT})
# ${ORIGIN_PATHS} -> ${ORIGIN_HOST}

server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;

    ignore_invalid_headers off;

${RESOLVER}

${GZIP_CONF}

${ORIGIN_LOCATIONS}    # Hystrix SSE metrics stream — must NOT be buffered.
    location = /hystrix.stream {
${PROXY_HDRS}
        proxy_set_header Host \$host;
        proxy_pass http://127.0.0.1:${FRONTCACHE_HTTP_PORT};
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
    }

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

    ignore_invalid_headers off;

${RESOLVER}

${GZIP_CONF}

    ssl_certificate     /etc/nginx/ssl/frontcache.crt;
    ssl_certificate_key /etc/nginx/ssl/frontcache.key;

${ORIGIN_LOCATIONS}    # Hystrix SSE metrics stream — must NOT be buffered.
    location = /hystrix.stream {
${PROXY_HDRS}
        proxy_set_header Host \$host;
        proxy_pass http://127.0.0.1:${FRONTCACHE_HTTP_PORT};
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
    }

    location / {
${PROXY_HDRS}
        proxy_set_header Host \$host;
        proxy_pass http://127.0.0.1:${FRONTCACHE_HTTP_PORT};
    }
}
EOF

ln -sf /etc/nginx/sites-available/default /etc/nginx/sites-enabled/default
nginx -t

# ---- run both processes ------------------------------------------------------
# Frontcache in the background; nginx as PID 1 in the foreground (via exec) so it
# receives Docker's SIGTERM directly and shuts down gracefully.
echo ">>> Starting Frontcache (embedded Jetty 12) on :$FRONTCACHE_HTTP_PORT ..."
FRONTCACHE_HTTP_PORT="$FRONTCACHE_HTTP_PORT" "$SERVER_DIR/server/bin/frontcache" &
FC_PID=$!

# If Frontcache dies on its own, stop nginx so the container exits and the
# restart policy brings the whole thing back up. (On `docker stop`, nginx gets
# SIGTERM directly and Docker tears down Frontcache with the container.)
( wait "$FC_PID"; echo ">>> Frontcache exited — stopping nginx"; nginx -s stop 2>/dev/null || true ) &

echo ">>> Starting nginx front door on :80 / :443 ..."
exec nginx -g 'daemon off;'
