#!/usr/bin/env bash
#
# Render the nginx site config that fronts Frontcache. Writes it to stdout.
#
# This is the ONE canonical template for this example. Both shapes call it:
#
#   * docker/render-site.sh      (shape A - nginx container, two-container compose)
#   * vm/configure-nginx.sh      (shape C - nginx on an Ubuntu host, after the installer)
#
# It came from the Frontcache distribution (scripts/dist/render-nginx-site.sh), where the
# installer's --with-nginx and the combined container image both rendered from it. The front
# door now lives here instead, so the distribution ships Frontcache alone. The only change
# from that copy is FRONTCACHE_HOST below: the upstream used to be 127.0.0.1 unconditionally,
# which is true on a VM but not when Frontcache is a separate container.
#
# The layout it produces:
#     /                -> local Frontcache  (80, and TLS-terminated 443 -> http $FRONTCACHE_HTTP_PORT)
#     /fc-dashboard.stream (and the legacy /hystrix.stream)
#                      -> local Frontcache, UNBUFFERED (they are Server-Sent-Events streams)
#     $ORIGIN_PATHS    -> $ORIGIN_HOST over https, bypassing the cache entirely
#
# The embedded Jetty 12 launcher speaks HTTP only, so nginx owns TLS. Responses proxied from
# Frontcache are gzipped here: Frontcache serves plaintext (its HttpClient only decodes
# gzip, so it cannot emit brotli) and nginx compresses for clients that accept it. nginx
# never re-compresses an already-encoded response.
#
# Environment:
#   FRONTCACHE_HOST       where Frontcache is (default 127.0.0.1 - the same host). Set it to a
#                         container/service name when nginx and Frontcache are separate
#                         containers. Written LITERALLY, so nginx resolves it once at
#                         config-load time and refuses to start if it does not resolve - which
#                         is what you want for your own backend, and why shape A's compose
#                         waits for the Frontcache container to be healthy first.
#   FRONTCACHE_HTTP_PORT  Frontcache's HTTP port (default 9080)
#   ORIGIN_HOST           backend for $ORIGIN_PATHS. EMPTY = omit those blocks entirely,
#                         so everything flows through Frontcache on '/' and `nginx -t`
#                         still passes. Never render a location against an empty host.
#   ORIGIN_PATHS          space-separated paths proxied straight to the origin
#   ORIGIN_SCHEME         https (default) or http. The distribution's copy always spoke https,
#                         which is right for a real origin; http is here so the example can run
#                         against a throwaway origin container with no certificate.
#   TLS_CERT / TLS_KEY    certificate paths for the 443 server block
#   NGINX_RESOLVER        resolver directive value, e.g. "127.0.0.11 ipv6=off valid=30s".
#                         Set  -> the origin host goes through an nginx VARIABLE, so it is
#                                 resolved per request; an origin DNS blip then cannot stop
#                                 nginx from loading or from serving cache hits on '/'.
#                         Unset -> the origin host is written literally and resolved once at
#                                 config-load time (nginx refuses to start if it will not
#                                 resolve, and never picks up a changed IP).
set -euo pipefail

FRONTCACHE_HOST="${FRONTCACHE_HOST:-127.0.0.1}"
FRONTCACHE_HTTP_PORT="${FRONTCACHE_HTTP_PORT:-9080}"
ORIGIN_HOST="${ORIGIN_HOST:-}"
ORIGIN_PATHS="${ORIGIN_PATHS:-/images/ /css/ /js/}"
ORIGIN_SCHEME="${ORIGIN_SCHEME:-https}"
TLS_CERT="${TLS_CERT:-/etc/nginx/ssl/frontcache.crt}"
TLS_KEY="${TLS_KEY:-/etc/nginx/ssl/frontcache.key}"
NGINX_RESOLVER="${NGINX_RESOLVER:-}"

# Common proxy headers - single-quoted so nginx runtime vars stay literal.
PROXY_HDRS='        proxy_http_version 1.1;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Port $server_port;'

# gzip_proxied any is required: nginx's default (off) skips compression for proxied
# responses. text/html is always compressed when gzip is on, regardless of gzip_types.
GZIP_CONF='    gzip on;
    gzip_vary on;
    gzip_proxied any;
    gzip_comp_level 5;
    gzip_min_length 256;
    gzip_http_version 1.1;
    gzip_types text/plain text/css text/xml text/javascript application/json application/javascript application/xml application/rss+xml image/svg+xml;'

RESOLVER_CONF=""
[ -n "$NGINX_RESOLVER" ] && RESOLVER_CONF="    resolver ${NGINX_RESOLVER};"

# SNI only applies to an https origin.
ORIGIN_SSL_CONF=""
[ "$ORIGIN_SCHEME" = "https" ] && ORIGIN_SSL_CONF="        proxy_ssl_server_name on;
        proxy_ssl_name ${ORIGIN_HOST};
"

# One location block per origin path. See NGINX_RESOLVER above for the two forms.
ORIGIN_LOCATIONS=""
if [ -n "$ORIGIN_HOST" ]; then
  for p in $ORIGIN_PATHS; do
    if [ -n "$NGINX_RESOLVER" ]; then
      ORIGIN_LOCATIONS+="    location ${p} {
${PROXY_HDRS}
        proxy_set_header Host ${ORIGIN_HOST};
${ORIGIN_SSL_CONF}        set \$fc_origin \"${ORIGIN_HOST}\";
        proxy_pass ${ORIGIN_SCHEME}://\$fc_origin;
    }

"
    else
      ORIGIN_LOCATIONS+="    location ${p} {
${PROXY_HDRS}
        proxy_set_header Host ${ORIGIN_HOST};
${ORIGIN_SSL_CONF}        proxy_pass ${ORIGIN_SCHEME}://${ORIGIN_HOST};
    }

"
    fi
  done
fi

# The two server blocks are identical apart from the listen/ssl lines, so they are emitted
# from one function rather than kept in sync by hand.
emit_server_block() {
  local listen_block="$1"
  cat <<BLOCK
server {
${listen_block}
    server_name _;

    ignore_invalid_headers off;

${RESOLVER_CONF}

${GZIP_CONF}

${ORIGIN_LOCATIONS}    # Dashboard SSE metrics stream - must NOT be buffered.
    # Both paths: the node serves the stream on /fc-dashboard.stream and, indefinitely, on the
    # pre-2.7 /hystrix.stream. A regex location so one block covers both - an exact-match location
    # on either name alone would leave the other to fall through to 'location /' and be buffered,
    # which is silent: the dashboard just receives nothing, forever, with no error anywhere.
    location ~ ^/(fc-dashboard|hystrix)\.stream$ {
${PROXY_HDRS}
        proxy_set_header Host \$host;
        proxy_pass http://${FRONTCACHE_HOST}:${FRONTCACHE_HTTP_PORT};
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
    }

    location / {
${PROXY_HDRS}
        proxy_set_header Host \$host;
        proxy_pass http://${FRONTCACHE_HOST}:${FRONTCACHE_HTTP_PORT};
    }
}
BLOCK
}

cat <<HEADER
# Managed by render-nginx-site.sh (frontcache/examples/front-door) - do not edit by hand.
# /                        -> Frontcache (80 and TLS-terminated 443 -> http ${FRONTCACHE_HOST}:${FRONTCACHE_HTTP_PORT})
# ${ORIGIN_PATHS} -> ${ORIGIN_HOST:-<not proxied: no origin host configured>}

HEADER

emit_server_block "    listen 80 default_server;
    listen [::]:80 default_server;"

echo

emit_server_block "    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;

    ssl_certificate     ${TLS_CERT};
    ssl_certificate_key ${TLS_KEY};"
