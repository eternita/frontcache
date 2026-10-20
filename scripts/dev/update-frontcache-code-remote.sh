#!/usr/bin/env bash
#
# Update the Frontcache Java code on a remote host: the ROOT.war web app AND the standalone
# launcher classpath (server/lib — the Jetty/EE10 jars + frontcache-server jar that
# `java -cp "server/lib/*" org.frontcache.server.FrontcacheJettyServer` runs from).
#
# It rebuilds frontcache-server locally, ships ROOT.war + server/lib, hot-swaps them, and
# restarts the systemd service. Use it for code-only redeploys (a core bugfix, or a dependency
# bump such as a new Jetty version) when the host is already provisioned and configured.
#
# It deploys into $REMOTE_BASE, default /opt/frontcache — the layout install-frontcache.sh
# creates, where /opt/frontcache is a SYMLINK to /opt/frontcache-server-<version>:
#
#   /opt/frontcache -> /opt/frontcache-server-2.9.0
#     server/frontcache-base/webapps/ROOT.war  <- swapped
#     server/lib/*.jar                         <- swapped (the launcher classpath)
#     server/bin/frontcache                    <- the launcher systemd runs
#     FRONTCACHE_HOME/{conf,cache,logs}        <- untouched (see --with-config)
#     runtime/                                 <- untouched (see below)
#
# Writing through the symlink lands in the versioned directory, so THE DIRECTORY KEEPS THE
# VERSION NAME IT WAS INSTALLED UNDER while the code inside it moves on: after this runs,
# /opt/frontcache-server-2.9.0 holds 2.10.0 jars. That is the trade for a code-only swap, and
# it is why the script prints the resolved path. An actual version cutover is
# install-frontcache.sh, which unpacks a new directory and flips the symlink.
#
# The default used to be the dev layout this script's sibling creates (~/opt/frontcache-server
# from deploy-build-remote.sh); reach it with REMOTE_BASE=/home/ubuntu/opt/frontcache-server.
# The $REMOTE_DIR variable that named it is gone, and setting it is an error rather than a
# silent deploy to the wrong tree.
#
# It never touches the bundle's embedded Java runtime (runtime/), which is what a
# per-platform bundle runs on: server/bin/frontcache prefers runtime/bin/java over $JAVA_HOME.
# So a jlink'd runtime whose module set is stale STAYS stale after a code-only deploy — if the
# new code reaches for a JDK module the bundle lacks, only that code path fails (2.10.0's
# "JVM CPU" blank column was exactly this). Fix by rebuilding and reinstalling the bundle.
#
# Why server/lib matters: the launcher classpath is independent of the war. If you bump a
# runtime dependency (e.g. Jetty) but ship only the war, the stale server/lib jars can be
# incompatible with the new bytecode. Concretely, targeting Java 25 (class v69) with the old
# Jetty 12.0.16 (asm-9.7.1) crashes the embedded server's annotation scan at startup
# (Unsupported class file major version 69) -> systemd crash loop. Syncing server/lib fixes it.
#
# By default it DOES NOT touch:
#   - Frontcache configs (FRONTCACHE_HOME/conf: resilience.properties, fc-logback.xml, fallbacks.conf, ...)
#   - the L2 Lucene cache, logs, or frontcache.id
#   - nginx (front door / gzip / TLS)
#   - the frontcache-console web app
#
# Flags:
#   --with-config   ALSO sync FRONTCACHE_HOME/conf from this repo (e.g. after changing
#                   resilience.properties or fc-logback.xml). nginx is still never touched.
#                   The two host-specific files are always preserved and never overwritten:
#                     - frontcache.id          (node identity, e.g. fc-us.example.com)
#                     - frontcache.properties  (origin host/port, API key, public domain)
#                   BEWARE on a live node: bots.conf, guard-rules.conf, fallbacks.conf,
#                   dynamic-urls.conf and resilience.properties are the five files the console's
#                   Configs tab writes with set-config, so a node's copies are usually AHEAD of
#                   this repo's (and carry its own .backup-* files beside them). This flag
#                   overwrites them with the repo's (the previous content is kept as
#                   <name>.bak-<timestamp>). Deploy code without it unless you mean to.
#   --no-libs       Skip the server/lib sync (war-only, the old lightweight behaviour). Use
#                   only when you are certain the launcher classpath is unchanged.
#   --war-jar <file>
#                   ALSO add <file> to the deployed war's WEB-INF/lib (repeatable) - e.g. a
#                   module found by ServiceLoader (org.frontcache.module.FrontcacheModule), which
#                   has to sit beside frontcache-core. Only the deployed copy changes; the
#                   checkout's own ROOT.war stays as built. A war deployed WITHOUT it has none of
#                   them: every deploy ships the whole war.
# Each overwritten file (war, jars via the whole dir, configs) is backed up on the remote as
# <name>.bak-<timestamp> for rollback.
#
# Staging happens under ~/.fc-deploy-stage on the remote (root filesystem), NOT /tmp: /tmp is
# a small tmpfs with a per-user quota, and a crash-looping service can fill it with war
# extractions — exactly when you need to deploy a fix.
#
# The remote host must already be running Frontcache: $REMOTE_BASE/server/frontcache-base/webapps/ROOT.war
# exists and the '$SERVICE_NAME' systemd unit is installed — from install-frontcache.sh (the
# /opt layout) or deploy-build-remote.sh (the dev layout under the login user's home).
#
# The ssh user need not own the install. When $REMOTE_BASE is not writable by it — the
# installer's default runs the service as the 'frontcache' system user — the file swaps run
# under sudo and each replaced file is chowned to match the directory it lands in, so the
# service user can still read it.
#
# Examples (user + identity file come from your ~/.ssh/config):
#   # code (war + server/lib):
#   REMOTE_HOST=ec2-123-456-789-123.compute-1.amazonaws.com ./update-frontcache-code-remote.sh
#
#   # code + frontcache configs (no nginx):
#   REMOTE_HOST=ec2-123-456-789-123.compute-1.amazonaws.com ./update-frontcache-code-remote.sh --with-config
#
#   # war only (skip server/lib):
#   REMOTE_HOST=ec2-123-456-789-123.compute-1.amazonaws.com ./update-frontcache-code-remote.sh --no-libs
#
#   # a host still on the dev layout (deploy-build-remote.sh):
#   REMOTE_HOST=my-host REMOTE_BASE=/home/ubuntu/opt/frontcache-server ./update-frontcache-code-remote.sh
#
set -euo pipefail

# ---- configuration (env-overridable)
# REMOTE_HOST is an ssh alias/hostname resolved via ~/.ssh/config (User + IdentityFile there).
REMOTE_HOST="${REMOTE_HOST:-ec2-123-456-789-123.compute-1.amazonaws.com}"
# Installed base directory on the remote - an ABSOLUTE path. The default is the symlink
# install-frontcache.sh maintains (/opt/frontcache -> /opt/frontcache-server-<version>);
# writing through it lands in the versioned directory, which is what the resolved path in the
# output is for. For the dev layout, pass REMOTE_BASE=/home/<user>/opt/frontcache-server.
REMOTE_BASE="${REMOTE_BASE:-/opt/frontcache}"
SERVICE_NAME="${SERVICE_NAME:-frontcache}"       # systemd unit name

# REMOTE_DIR was a path relative to the remote user's HOME, so honouring it now would deploy
# somewhere else entirely and restart a service still serving the old code from /opt. Refuse.
if [ -n "${REMOTE_DIR:-}" ]; then
  echo "ERROR: REMOTE_DIR is retired - it was relative to the remote \$HOME. Use REMOTE_BASE," >&2
  echo "       an absolute path (e.g. REMOTE_BASE=/home/ubuntu/$REMOTE_DIR/frontcache-server)." >&2
  exit 1
fi

case "$REMOTE_BASE" in
  /*) ;;
  *) echo "ERROR: REMOTE_BASE must be an absolute path, got: $REMOTE_BASE" >&2; exit 1 ;;
esac
REMOTE_BASE="${REMOTE_BASE%/}"   # one trailing slash would make every printed path odd

# ---- args
WITH_CONFIG=false
SYNC_LIBS=true
WAR_JARS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --with-config) WITH_CONFIG=true; shift ;;
    --no-libs) SYNC_LIBS=false; shift ;;
    --war-jar)
      [ $# -ge 2 ] || { echo "ERROR: --war-jar needs a file" >&2; exit 1; }
      [ -f "$2" ] || { echo "ERROR: --war-jar: no such file: $2" >&2; exit 1; }
      WAR_JARS+=("$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"); shift 2 ;;
    -h|--help) grep -E '^#( |$)' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "ERROR: unknown argument: $1 (use --with-config, --no-libs, --war-jar <file>, or --help)" >&2; exit 1 ;;
  esac
done

# local frontcache-server dir + the artifacts the build produces
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"   # this script lives in scripts/dev
LOCAL_SERVER_DIR="$REPO_ROOT/frontcache-server"
ROOT_WAR="$LOCAL_SERVER_DIR/server/frontcache-base/webapps/ROOT.war"  # from build.finalizedBy(copyWar)
LOCAL_LIB_DIR="$LOCAL_SERVER_DIR/server/lib"                          # from build.finalizedBy(copyServerLibs)

# FRONTCACHE_HOME/conf synced only with --with-config. Host-specific files are never overwritten.
LOCAL_CONF_DIR="$LOCAL_SERVER_DIR/FRONTCACHE_HOME/conf"
CONFIG_EXCLUDES=(frontcache.id frontcache.properties)
# Anything matching these is never shipped: a stray .DS_Store from a macOS checkout is how one
# ended up in a live conf/ directory, and a *.backup-* is something set-config wrote on a node.
CONFIG_SKIP_PATTERNS=('.DS_Store' '._*' '*.bak-*' '*.backup-*' '*.new' '*~')

# remote staging dir on the root filesystem (home), NOT /tmp (small tmpfs w/ per-user quota).
STAGE_REL=".fc-deploy-stage"   # relative to remote $HOME

# The bytecode level this build produces, read from the toolchain in build.gradle so there is no
# second copy of it. Empty (an unrecognised build.gradle) simply skips the runtime check below.
TARGET_RELEASE="$(grep -oE 'JavaLanguageVersion\.of\([0-9]+\)' "$REPO_ROOT/build.gradle" 2>/dev/null \
  | head -1 | grep -oE '[0-9]+' || true)"
# ------------------------------------------------------------------------------

SSH_TARGET="$REMOTE_HOST"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new)

# ---- build a fresh ROOT.war + server/lib -------------------------------------
# build.finalizedBy(copyWar) places the war at $ROOT_WAR; copyServerLibs (a Sync task)
# refreshes $LOCAL_LIB_DIR. Build before touching the remote so a compile/test failure
# aborts the deploy without stopping the live service.
echo ">>> Building frontcache-server (./gradlew :frontcache-server:build) ..."
( cd "$REPO_ROOT" && ./gradlew :frontcache-server:build )

if [ ! -f "$ROOT_WAR" ]; then
  echo "ERROR: build did not produce $ROOT_WAR" >&2
  exit 1
fi
echo "    war: $ROOT_WAR ($(du -h "$ROOT_WAR" | cut -f1))"

# --war-jar: ship a copy of the war with the extra jars in WEB-INF/lib. zip copies every existing
# entry of the war as it is.
DEPLOY_WAR="$ROOT_WAR"
if [ ${#WAR_JARS[@]} -gt 0 ]; then
  command -v zip > /dev/null 2>&1 || { echo "ERROR: --war-jar needs zip" >&2; exit 1; }
  WAR_WORK="$(mktemp -d "${TMPDIR:-/tmp}/fc-war.XXXXXX")"
  trap 'rm -rf "$WAR_WORK"' EXIT
  DEPLOY_WAR="$WAR_WORK/ROOT.war"
  cp "$ROOT_WAR" "$DEPLOY_WAR"
  mkdir -p "$WAR_WORK/stage/WEB-INF/lib"
  for j in "${WAR_JARS[@]}"; do
    name="$(basename "$j")"
    if unzip -l "$DEPLOY_WAR" "WEB-INF/lib/$name" > /dev/null 2>&1; then
      echo "ERROR: the war already has WEB-INF/lib/$name" >&2; exit 1
    fi
    cp "$j" "$WAR_WORK/stage/WEB-INF/lib/$name"
    echo "    + WEB-INF/lib/$name"
  done
  ( cd "$WAR_WORK/stage" && zip -q -X "$DEPLOY_WAR" WEB-INF/lib/*.jar )
fi

LOCAL_LIB_TAR=""
if $SYNC_LIBS; then
  if [ ! -d "$LOCAL_LIB_DIR" ] || [ -z "$(ls "$LOCAL_LIB_DIR"/*.jar 2>/dev/null)" ]; then
    echo "ERROR: server/lib not found or empty: $LOCAL_LIB_DIR (expected copyServerLibs output)" >&2
    exit 1
  fi
  LOCAL_LIB_TAR="$(mktemp -t fc-serverlib.XXXXXX).tar.gz"
  # COPYFILE_DISABLE + excludes keep macOS AppleDouble/.DS_Store out of the tarball.
  COPYFILE_DISABLE=1 tar --exclude='._*' --exclude='.DS_Store' \
    -czf "$LOCAL_LIB_TAR" -C "$LOCAL_LIB_DIR" .
  echo "    server/lib: $(ls "$LOCAL_LIB_DIR"/*.jar | wc -l | tr -d ' ') jars ($(du -h "$LOCAL_LIB_TAR" | cut -f1) packed)"
fi

# ---- upload artifacts to the remote staging dir (root fs, not /tmp) ----------
# The same check the swap block makes, one round trip earlier: shipping ~30MB to a host whose
# base directory is not an install is a slow way to find out.
echo ">>> Uploading artifacts to $SSH_TARGET:~/$STAGE_REL ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "
  set -e
  if [ ! -f \"$REMOTE_BASE/server/frontcache-base/webapps/ROOT.war\" ]; then
    echo \"ERROR: $REMOTE_BASE/server/frontcache-base/webapps/ROOT.war not found\" >&2
    echo \"       Set REMOTE_BASE, or install with scripts/dist/install-frontcache.sh first.\" >&2
    exit 1
  fi
  # A per-platform bundle runs on its own jlink'd runtime (server/bin/frontcache prefers
  # runtime/bin/java over \$JAVA_HOME) and this script never replaces it. Shipping newer
  # bytecode onto an older one is not a degraded deploy, it is a crash loop on every start -
  # so it is refused here, before the service is stopped. Reinstall the bundle instead.
  JAVA_BIN=\"$REMOTE_BASE/runtime/bin/java\"
  if [ -n \"$TARGET_RELEASE\" ] && [ -x \"\$JAVA_BIN\" ]; then
    RT=\$(\"\$JAVA_BIN\" -XshowSettings:properties -version 2>&1 \\
      | awk -F'= *' '/^ *java\\.specification\\.version/{print \$2}' | tr -d ' ' | head -1)
    case \"\$RT\" in
      ''|*[!0-9]*) echo \"   (embedded runtime: version unreadable, skipping the check)\" ;;
      *) if [ \"\$RT\" -lt \"$TARGET_RELEASE\" ]; then
           echo \"ERROR: the bundle's embedded runtime is Java \$RT but this build targets Java $TARGET_RELEASE.\" >&2
           echo \"       The new war would not start. Rebuild and reinstall the bundle\" >&2
           echo \"       (scripts/dist/build-runtime-bundles.sh + install-frontcache.sh) and retry.\" >&2
           exit 1
         fi
         echo \"   embedded runtime: Java \$RT (this build targets $TARGET_RELEASE)\" ;;
    esac
  fi

  rm -rf ~/$STAGE_REL && mkdir -p ~/$STAGE_REL"
scp "${SSH_OPTS[@]}" "$DEPLOY_WAR" "$SSH_TARGET:$STAGE_REL/ROOT.war.new"
if $SYNC_LIBS; then
  scp "${SSH_OPTS[@]}" "$LOCAL_LIB_TAR" "$SSH_TARGET:$STAGE_REL/server-lib.tar.gz"
  rm -f "$LOCAL_LIB_TAR"
fi

# ---- optionally stage Frontcache configs (--with-config) ---------------------
# The swap block below applies them while the service is stopped (so one restart picks up
# everything). Host-specific files are never staged, so they're left untouched on the remote.
if $WITH_CONFIG; then
  if [ ! -d "$LOCAL_CONF_DIR" ]; then
    echo "ERROR: config dir not found: $LOCAL_CONF_DIR" >&2
    exit 1
  fi
  echo ">>> Staging Frontcache configs (host-specific files preserved) ..."
  UPLOAD_FILES=()
  for f in "$LOCAL_CONF_DIR"/*; do
    [ -f "$f" ] || continue
    base="$(basename "$f")"
    skip=false
    for ex in "${CONFIG_EXCLUDES[@]}"; do [ "$base" = "$ex" ] && skip=true; done
    if $skip; then echo "    skip (host-specific): $base"; continue; fi
    for pat in "${CONFIG_SKIP_PATTERNS[@]}"; do
      # shellcheck disable=SC2053 - the right-hand side is a pattern on purpose
      [[ "$base" == $pat ]] && skip=true
    done
    if $skip; then echo "    skip (not a config): $base"; continue; fi
    UPLOAD_FILES+=("$f")
    echo "    upload: $base"
  done
  ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "mkdir -p ~/$STAGE_REL/conf-new"
  scp "${SSH_OPTS[@]}" "${UPLOAD_FILES[@]}" "$SSH_TARGET:$STAGE_REL/conf-new/"
fi

echo ">>> Swapping artifacts and restarting '$SERVICE_NAME' on $SSH_TARGET ..."
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "
  set -e
  STAGE=\"\$HOME/$STAGE_REL\"
  SERVER_DIR=\"$REMOTE_BASE\"
  WEBAPPS=\"\$SERVER_DIR/server/frontcache-base/webapps\"
  LIB_DIR=\"\$SERVER_DIR/server/lib\"

  if [ ! -f \"\$WEBAPPS/ROOT.war\" ]; then
    echo \"ERROR: \$WEBAPPS/ROOT.war not found on remote - is $REMOTE_BASE the install base?\" >&2
    echo \"       Install with scripts/dist/install-frontcache.sh, or set REMOTE_BASE.\" >&2
    rm -rf \"\$STAGE\"
    exit 1
  fi

  # /opt/frontcache is a symlink to /opt/frontcache-server-<version>, so say where this lands -
  # the directory keeps the version it was INSTALLED under, whatever the code in it now is.
  BASE_REAL=\$(readlink -f \"\$SERVER_DIR\")
  [ \"\$BASE_REAL\" = \"\$SERVER_DIR\" ] || echo \"   base: \$SERVER_DIR -> \$BASE_REAL\"

  # The installer runs the service as its own system user and owns the tree as that user, so
  # the ssh user usually cannot write here; on the dev layout it owns everything and sudo
  # would be noise. Decide once, from the directory we are about to write to.
  SUDO=\"\"
  [ -w \"\$WEBAPPS\" ] || SUDO=sudo

  # stop so Jetty releases the war and its extracted copy before we swap
  sudo systemctl stop $SERVICE_NAME

  TS=\$(date +%Y%m%d-%H%M%S)

  # back up the current war (timestamped) for quick rollback, then swap in the new one.
  # chown --reference: the staged file arrived owned by the ssh user, and the service user has
  # to be able to read what it is about to deploy.
  \$SUDO cp -p \"\$WEBAPPS/ROOT.war\" \"\$WEBAPPS/ROOT.war.bak-\$TS\"
  \$SUDO mv \"\$STAGE/ROOT.war.new\" \"\$WEBAPPS/ROOT.war\"
  [ -z \"\$SUDO\" ] || \$SUDO chown --reference=\"\$WEBAPPS\" \"\$WEBAPPS/ROOT.war\"

  # Jetty extracts the war (the launcher hands WebAppContext the war file itself; the dev
  # layout's ROOT.xml sets extractWAR=true): drop any stale exploded webapp so the new war is
  # re-extracted on start rather than serving stale classes.
  \$SUDO rm -rf \"\$WEBAPPS/ROOT\"

  # Jetty's own temp dirs live in a SHARED /tmp, and a co-located console is a second Jetty
  # serving JSPs out of its extraction right now - deleting that under it breaks it until the
  # next restart, which this script does not do. So skip the sweep when one is running; the
  # node's own extraction is keyed on the war's timestamp and is re-made on start anyway.
  if systemctl is-active --quiet frontcache-console 2>/dev/null; then
    echo \"   (kept /tmp/jetty-*: frontcache-console is running and shares /tmp)\"
  else
    \$SUDO rm -rf /tmp/jetty-* 2>/dev/null || true
  fi

  # sync server/lib (launcher classpath - server/bin/frontcache runs java -cp \"lib/*\", so it
  # picks up whatever is here). Jar filenames are version-stamped, so replace the whole set
  # rather than merge, or old + new jars would coexist on the classpath.
  if [ -f \"\$STAGE/server-lib.tar.gz\" ]; then
    if [ ! -d \"\$LIB_DIR\" ]; then
      echo \"ERROR: \$LIB_DIR not found on remote - is $REMOTE_BASE the install base?\" >&2
      rm -rf \"\$STAGE\"; exit 1
    fi
    \$SUDO cp -a \"\$LIB_DIR\" \"\$LIB_DIR.bak-\$TS\"
    \$SUDO rm -f \"\$LIB_DIR\"/*.jar
    \$SUDO tar -xzf \"\$STAGE/server-lib.tar.gz\" -C \"\$LIB_DIR\"
    [ -z \"\$SUDO\" ] || \$SUDO chown -R --reference=\"\$LIB_DIR\" \"\$LIB_DIR\"
    echo \"   server/lib updated (\$(ls \"\$LIB_DIR\"/*.jar | wc -l | tr -d ' ') jars; previous kept at server/lib.bak-\$TS)\"
  fi

  # apply staged configs (only present with --with-config). Each overwritten file is backed
  # up as <name>.bak-\$TS. frontcache.id / frontcache.properties were never staged.
  if [ -d \"\$STAGE/conf-new\" ]; then
    CONF_DIR=\"\$SERVER_DIR/FRONTCACHE_HOME/conf\"
    for nf in \"\$STAGE\"/conf-new/*; do
      [ -e \"\$nf\" ] || continue
      base=\$(basename \"\$nf\")
      [ -f \"\$CONF_DIR/\$base\" ] && \$SUDO cp -p \"\$CONF_DIR/\$base\" \"\$CONF_DIR/\$base.bak-\$TS\"
      \$SUDO mv \"\$nf\" \"\$CONF_DIR/\$base\"
      [ -z \"\$SUDO\" ] || \$SUDO chown --reference=\"\$CONF_DIR\" \"\$CONF_DIR/\$base\"
      echo \"   config updated: \$base (previous kept at \$base.bak-\$TS)\"
    done
  fi

  rm -rf \"\$STAGE\"

  sudo systemctl start $SERVICE_NAME
  sleep 3
  sudo systemctl --no-pager status $SERVICE_NAME | head -5 || true
  echo \"   (previous war kept at \$WEBAPPS/ROOT.war.bak-\$TS)\"
"

SUFFIX="(configs, cache & nginx untouched)"
$WITH_CONFIG && SUFFIX="(frontcache.id, frontcache.properties, cache & nginx untouched)"
if $SYNC_LIBS; then
  echo ">>> Done. Frontcache code (war + server/lib) updated on $REMOTE_HOST $SUFFIX."
else
  echo ">>> Done. Frontcache code (war only, --no-libs) updated on $REMOTE_HOST $SUFFIX."
fi
echo "    Base:     $REMOTE_BASE/server (war + lib; FRONTCACHE_HOME and runtime/ untouched)"
echo "    Verify:   curl -sI https://$REMOTE_HOST/ | head"
echo "    Logs:     ssh $SSH_TARGET 'sudo journalctl -u $SERVICE_NAME -f'"
echo "    Rollback: on the remote, restore the newest $REMOTE_BASE/server/frontcache-base/webapps/ROOT.war.bak-*"
echo "              (and $REMOTE_BASE/server/lib.bak-*) then 'sudo systemctl restart $SERVICE_NAME'"
