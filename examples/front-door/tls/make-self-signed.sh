#!/usr/bin/env bash
#
# Generate a self-signed certificate for the nginx front door, if one is not already there.
#
# This replaces the keystore extraction the distribution used to do. That step ran
# `keytool -importkeystore | openssl pkcs12` against the Jetty DEMO keystore inside the
# Frontcache bundle - it reached into a private path of the distribution and hard-coded its
# demo passwords, purely to reuse a certificate that was self-signed anyway. Generating one
# here gets the same result and reaches into nothing.
#
# It is a DEMO certificate either way: browsers will warn, and it is not what you serve real
# traffic with. For production, put your own frontcache.crt / frontcache.key in the same
# directory (or mount them there) BEFORE starting - existing files are never overwritten.
#
#   ./make-self-signed.sh [dir] [common-name]
#
# Env: TLS_DIR (default /etc/nginx/ssl), TLS_CN (default localhost), TLS_DAYS (default 825)
set -euo pipefail

TLS_DIR="${1:-${TLS_DIR:-/etc/nginx/ssl}}"
TLS_CN="${2:-${TLS_CN:-localhost}}"
TLS_DAYS="${TLS_DAYS:-825}"

CRT="$TLS_DIR/frontcache.crt"
KEY="$TLS_DIR/frontcache.key"

if [ -s "$CRT" ] && [ -s "$KEY" ]; then
  echo ">>> Using the certificate already at $TLS_DIR"
  exit 0
fi

command -v openssl >/dev/null 2>&1 || { echo >&2 "ERROR: openssl not found - install it, or supply $CRT and $KEY yourself"; exit 1; }

echo ">>> No certificate at $TLS_DIR - generating a SELF-SIGNED one for CN=$TLS_CN"
echo "    Browsers will warn. Replace it with a real certificate before serving real traffic."
mkdir -p "$TLS_DIR"

# -addext subjectAltName is what modern clients actually check; CN alone is ignored.
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$KEY" -out "$CRT" -days "$TLS_DAYS" \
  -subj "/CN=$TLS_CN" \
  -addext "subjectAltName=DNS:$TLS_CN,DNS:localhost,IP:127.0.0.1" \
  2>/dev/null

chmod 600 "$KEY"
echo ">>> Wrote $CRT and $KEY (valid $TLS_DAYS days)"
