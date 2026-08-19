#!/usr/bin/env bash
#
# Render the nginx site config that fronts Frontcache. Writes it to stdout.
#
# This is the ONE canonical template. It was previously duplicated in three places - the
# container entrypoint, the remote nginx configurator, and (would have been) the installer -
# which is exactly the kind of thing that drifts. Both customer-facing paths now call this:
#
#   * scripts/dist/install-frontcache.sh          (VM / bare metal, --with-nginx)
#   * scripts/docker/fc-server/entrypoint.sh      (the full container image)
#
# The layout it produces:
#     /                -> local Frontcache  (80, and TLS-terminated 443 -> http $FRONTCACHE_HTTP_PORT)
#     /hystrix.stream  -> local Frontcache, UNBUFFERED (it is a Server-Sent-Events stream)
#     $ORIGIN_PATHS    -> $ORIGIN_HOST over https, bypassing the cache entirely
#
# The embedded Jetty 12 launcher speaks HTTP only, so nginx owns TLS. Responses proxied from
# Frontcache are gzipped here: Frontcache serves plaintext (its HttpClient only decodes
# gzip, so it cannot emit brotli) and nginx compresses for clients that accept it. nginx
# never re-compresses an already-encoded response.
#
# Environment:
#   FRONTCACHE_HTTP_PORT  local Frontcache port (default 9080)
#   ORIGIN_HOST           backend for $ORIGIN_PATHS. EMPTY = omit those blocks entirely,
#                         so everything flows through Frontcache on '/' and `nginx -t`
#                         still passes. Never render a location against an empty host.
#   ORIGIN_PATHS          space-separated paths proxied straight to the origin
#   TLS_CERT / TLS_KEY    certificate paths for the 443 server block
#   NGINX_RESOLVER        resolver directive value, e.g. "127.0.0.11 ipv6=off valid=30s".
#                         Set  -> the origin host goes through an nginx VARIABLE, so it is
#                                 resolved per request; an origin DNS blip then cannot stop
#                                 nginx from loading or from serving cache hits on '/'.
#                         Unset -> the origin host is written literally and resolved once at
#                                 config-load time (nginx refuses to start if it will not
#                                 resolve, and never picks up a changed IP).
set -euo pipefail

FRONTCACHE_HTTP_PORT="${FRONTCACHE_HTTP_PORT:-9080}"
ORIGIN_HOST="${ORIGIN_HOST:-}"
ORIGIN_PATHS="${ORIGIN_PATHS:-/o9r/ /fs/ /pi/ /st/ /images/ /css/ /js/ /page-cache/}"
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

# One location block per origin path. See NGINX_RESOLVER above for the two forms.
ORIGIN_LOCATIONS=""
if [ -n "$ORIGIN_HOST" ]; then
  for p in $ORIGIN_PATHS; do
    if [ -n "$NGINX_RESOLVER" ]; then
      ORIGIN_LOCATIONS+="    location ${p} {
${PROXY_HDRS}
        proxy_set_header Host ${ORIGIN_HOST};
        proxy_ssl_server_name on;
        proxy_ssl_name ${ORIGIN_HOST};
        set \$fc_origin \"${ORIGIN_HOST}\";
        proxy_pass https://\$fc_origin;
    }

"
    else
      ORIGIN_LOCATIONS+="    location ${p} {
${PROXY_HDRS}
        proxy_set_header Host ${ORIGIN_HOST};
        proxy_ssl_server_name on;
        proxy_ssl_name ${ORIGIN_HOST};
        proxy_pass https://${ORIGIN_HOST};
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
BLOCK
}

cat <<HEADER
# Managed by Frontcache (scripts/dist/render-nginx-site.sh) - do not edit by hand.
# /                        -> local Frontcache (80 and TLS-terminated 443 -> http ${FRONTCACHE_HTTP_PORT})
# ${ORIGIN_PATHS} -> ${ORIGIN_HOST:-<not proxied: no origin host configured>}

HEADER

emit_server_block "    listen 80 default_server;
    listen [::]:80 default_server;"

echo

emit_server_block "    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;

    ssl_certificate     ${TLS_CERT};
    ssl_certificate_key ${TLS_KEY};"
