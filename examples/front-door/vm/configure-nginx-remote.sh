#!/usr/bin/env bash
#
# Shape C, over ssh - configure the nginx front door on a REMOTE Frontcache host.
#
# Same result as configure-nginx.sh, driven from your laptop instead of run on the box: it
# ships the renderer and the cert helper to the host and runs configure-nginx.sh there. Use it
# when you are managing a handful of hosts by hand and would rather not ssh into each one.
#
# This came from the Frontcache repo (scripts/bash/configure-nginx-remote.sh), where it
# carried its OWN copy of the nginx site template - one that had already drifted from the
# canonical one (no resolver support, no proxy_ssl_name, hard-coded origin paths). That copy
# is gone: this now renders from ../render-nginx-site.sh like everything else here, which is
# the whole reason the front door lives in one place.
#
# REMOTE_HOST is an ssh alias or hostname resolved through ~/.ssh/config (User and
# IdentityFile belong there, not here). The remote user needs passwordless sudo.
#
#   REMOTE_HOST=fc-us ./configure-nginx-remote.sh --origin-host origin.example.com
#   REMOTE_HOST=fc-us ./configure-nginx-remote.sh --origin-host origin.example.com --dry-run
#
# Every argument is passed straight through to configure-nginx.sh on the remote host, so see
# `./configure-nginx.sh --help` for the full list.
set -euo pipefail

SELF_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

REMOTE_HOST="${REMOTE_HOST:-}"
REMOTE_TMP="${REMOTE_TMP:-/tmp/frontcache-front-door}"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new)

[ -n "$REMOTE_HOST" ] || { echo >&2 "ERROR: set REMOTE_HOST (an ssh alias from ~/.ssh/config)"; exit 1; }

echo ">>> Shipping the front-door scripts to $REMOTE_HOST:$REMOTE_TMP ..."
ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "rm -rf '$REMOTE_TMP' && mkdir -p '$REMOTE_TMP/vm' '$REMOTE_TMP/tls'"
scp -q "${SSH_OPTS[@]}" "$SELF_DIR/../render-nginx-site.sh"  "$REMOTE_HOST:$REMOTE_TMP/"
scp -q "${SSH_OPTS[@]}" "$SELF_DIR/../tls/make-self-signed.sh" "$REMOTE_HOST:$REMOTE_TMP/tls/"
scp -q "${SSH_OPTS[@]}" "$SELF_DIR/configure-nginx.sh"         "$REMOTE_HOST:$REMOTE_TMP/vm/"

echo ">>> Running configure-nginx.sh on $REMOTE_HOST ..."
# "$@" is expanded HERE and quoted for the remote shell, so arguments with spaces
# (--origin-paths "/a/ /b/") survive the hop intact.
REMOTE_ARGS=""
for a in "$@"; do REMOTE_ARGS+=" $(printf '%q' "$a")"; done
ssh -t "${SSH_OPTS[@]}" "$REMOTE_HOST" "sudo bash '$REMOTE_TMP/vm/configure-nginx.sh'$REMOTE_ARGS"

ssh "${SSH_OPTS[@]}" "$REMOTE_HOST" "rm -rf '$REMOTE_TMP'"
echo ">>> Done. $REMOTE_HOST is fronted by nginx on :80 / :443."
