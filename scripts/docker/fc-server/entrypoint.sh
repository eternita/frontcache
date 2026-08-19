#!/usr/bin/env bash
#
# Entrypoint for the FULL Frontcache server image: nginx front door + Frontcache
# standalone in one container.
#
# Runs the same two processes as the single-host VM install
# (scripts/dist/install-frontcache.sh --with-nginx):
#   1. Frontcache standalone (embedded Jetty 12, HTTP only) on $FRONTCACHE_HTTP_PORT
#   2. nginx on 80/443 - terminates TLS, proxies / -> Frontcache and $ORIGIN_PATHS -> $ORIGIN_HOST
#
# The nginx site config and the TLS cert are generated at startup from env vars, so a single
# image serves any deployment. Three pieces are shared rather than duplicated: config seeding
# and origin resolution with the slim image (seed-conf.sh), and the nginx site template with
# the VM installer (render-nginx-site.sh).
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

# ---- nginx site config -------------------------------------------------------
# Rendered by the shared template render-nginx-site.sh (copied into the image), which is the
# SAME renderer the VM installer uses. This config used to be duplicated once per delivery
# channel, which is exactly the kind of thing that drifts apart.
#
# NGINX_RESOLVER points at Docker's embedded DNS, which makes the renderer emit the origin
# host through an nginx variable so it is resolved per request rather than at config-load
# time. An origin DNS blip then cannot stop nginx from loading, and so never takes down cache
# serving on '/'. With no origin host known at all, the renderer omits the origin-path blocks
# entirely rather than rendering them against an empty name.
if [ -z "${ORIGIN_HOST:-}" ]; then
  echo ">>> No origin host known - the origin-path passthrough blocks will be omitted."
  echo "    Every path will be served through Frontcache on '/'. Set ORIGIN_HOST (or"
  echo "    front-cache.origin-host in the mounted conf) to proxy the origin paths directly."
fi

echo ">>> Rendering nginx site config (origin: ${ORIGIN_HOST:-<none>}) ..."
FRONTCACHE_HTTP_PORT="$FRONTCACHE_HTTP_PORT" \
ORIGIN_HOST="${ORIGIN_HOST:-}" \
ORIGIN_PATHS="$ORIGIN_PATHS" \
NGINX_RESOLVER="${NGINX_RESOLVER:-127.0.0.11 ipv6=off valid=30s}" \
TLS_CERT=/etc/nginx/ssl/frontcache.crt \
TLS_KEY=/etc/nginx/ssl/frontcache.key \
  bash /opt/fc-server/render-nginx-site.sh > /etc/nginx/sites-available/default

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
