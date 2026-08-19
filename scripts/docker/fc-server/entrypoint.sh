#!/usr/bin/env bash
#
# Entrypoint for the FULL Frontcache server image: nginx front door + Frontcache
# standalone in one container.
#
# Runs the same two processes as the single-host VM install
# (scripts/bash/install-frontcache-server-remote.sh + configure-nginx-remote.sh):
#   1. Frontcache standalone (embedded Jetty 12, HTTP only) on $FRONTCACHE_HTTP_PORT
#   2. nginx on 80/443 - terminates TLS, proxies / -> Frontcache and $ORIGIN_PATHS -> $ORIGIN_HOST
#
# The nginx site config and the TLS cert are generated here at startup from env vars, so a
# single image serves any deployment. Config seeding and origin resolution are shared with
# the slim image - see seed-conf.sh.
#
# For an ingress/ALB deployment that needs no nginx, use the -slim image instead.
set -euo pipefail

# ---- configuration (env-overridable) ----------------------------------------
SERVER_DIR="/opt/frontcache-server"
DEFAULTS_DIR="/opt/frontcache-defaults/conf"
CONF_DIR="$SERVER_DIR/FRONTCACHE_HOME/conf"
FRONTCACHE_HTTP_PORT="${FRONTCACHE_HTTP_PORT:-9080}"

ORIGIN_PATHS="${ORIGIN_PATHS:-/o9r/ /fs/ /pi/ /st/ /images/ /css/ /js/ /page-cache/}"

# Jetty demo keystore credentials (same defaults as configure-nginx-remote.sh)
KEYSTORE="${KEYSTORE:-$SERVER_DIR/server/frontcache-base/etc/keystore}"
KEYSTORE_STOREPASS="${KEYSTORE_STOREPASS:-storepwd}"
KEYSTORE_KEYPASS="${KEYSTORE_KEYPASS:-keypwd}"
KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-jetty}"
# -----------------------------------------------------------------------------

# shellcheck source=seed-conf.sh
. /opt/fc-server/seed-conf.sh

# ---- config: seed missing files, then resolve the origin ----------------------
seed_conf "$DEFAULTS_DIR" "$CONF_DIR"
resolve_origin_host "$CONF_DIR/frontcache.properties"

# ---- TLS: extract the Jetty keystore into PEM for nginx ----------------------
# A real certificate should be mounted at /etc/nginx/ssl instead; if both files are
# already there we leave them alone, so mounting a real cert just works.
if [ -s /etc/nginx/ssl/frontcache.crt ] && [ -s /etc/nginx/ssl/frontcache.key ]; then
  echo ">>> Using the TLS certificate already present at /etc/nginx/ssl"
else
  echo ">>> Extracting TLS cert from the Jetty keystore (SELF-SIGNED DEMO CERT - replace for production) ..."
  mkdir -p /etc/nginx/ssl
  TMP="$(mktemp -d)"
  keytool -importkeystore -noprompt \
    -srckeystore "$KEYSTORE" -srcstorepass "$KEYSTORE_STOREPASS" -srckeypass "$KEYSTORE_KEYPASS" -srcalias "$KEYSTORE_ALIAS" \
    -destkeystore "$TMP/fc.p12" -deststoretype PKCS12 -deststorepass "$KEYSTORE_STOREPASS"
  openssl pkcs12 -in "$TMP/fc.p12" -passin pass:"$KEYSTORE_STOREPASS" -nokeys  -out /etc/nginx/ssl/frontcache.crt
  openssl pkcs12 -in "$TMP/fc.p12" -passin pass:"$KEYSTORE_STOREPASS" -nodes -nocerts -out /etc/nginx/ssl/frontcache.key
  chmod 600 /etc/nginx/ssl/frontcache.key
  rm -rf "$TMP"
fi

# ---- nginx site config (rendered from env) -----------------------------------
# Common proxy headers - single-quoted so nginx runtime vars stay literal.
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
#
# With no origin host known, the blocks are skipped entirely rather than rendered against
# an empty name - everything then goes through Frontcache on `/`, which is the correct
# degradation and keeps `nginx -t` valid.
ORIGIN_LOCATIONS=""
if [ -n "${ORIGIN_HOST:-}" ]; then
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
else
  echo ">>> No origin host known - skipping the origin-path passthrough blocks."
  echo "    Every path will be served through Frontcache on '/'. Set ORIGIN_HOST (or"
  echo "    front-cache.origin-host in the mounted conf) to proxy \$ORIGIN_PATHS directly."
fi

# Docker's embedded DNS resolver; used for the variable-based origin proxy_pass above.
RESOLVER='    resolver 127.0.0.11 ipv6=off valid=30s;'

echo ">>> Rendering nginx site config (origin: ${ORIGIN_HOST:-<none>}) ..."
# Unquoted heredoc: our shell vars expand; nginx runtime vars are escaped as \$.
cat > /etc/nginx/sites-available/default <<EOF
# Managed by the fc-server entrypoint - do not edit by hand.
# /                        -> local Frontcache (80 and TLS-terminated 443 -> http ${FRONTCACHE_HTTP_PORT})
# ${ORIGIN_PATHS} -> ${ORIGIN_HOST:-<not proxied: no origin host configured>}

server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;

    ignore_invalid_headers off;

${RESOLVER}

${GZIP_CONF}

${ORIGIN_LOCATIONS}    # Hystrix SSE metrics stream - must NOT be buffered.
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

${ORIGIN_LOCATIONS}    # Hystrix SSE metrics stream - must NOT be buffered.
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

# If Frontcache dies on its own, stop nginx so the container exits and the restart policy
# brings the whole thing back up. (On `docker stop`, nginx gets SIGTERM directly and Docker
# tears down Frontcache with the container.)
#
# This polls with `kill -0` rather than `wait`: the watchdog runs in a subshell, and
# $FC_PID is that subshell's SIBLING, not its child, so `wait` refuses it with
# "pid N is not a child of this shell" and the watchdog fires immediately - killing nginx
# seconds after startup. That was the behaviour before this loop replaced it.
( while kill -0 "$FC_PID" 2>/dev/null; do sleep 5; done
  echo ">>> Frontcache exited - stopping nginx"
  nginx -s stop 2>/dev/null || true ) &

echo ">>> Starting nginx front door on :80 / :443 ..."
exec nginx -g 'daemon off;'
