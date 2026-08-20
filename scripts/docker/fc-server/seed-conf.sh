#!/usr/bin/env bash
#
# Config handling for the Frontcache server image. Sourced by entrypoint.sh.
#
# Two jobs:
#
#  1. seed_conf - copy any MISSING config file from the image's read-only defaults into
#     the live conf directory. This is what makes a persisted conf volume upgrade-safe:
#     Docker seeds a named volume from the image only the FIRST time it is used, so a
#     newer image's config files would otherwise never reach an existing volume. Seeding
#     at startup delivers new files while never touching one the operator has edited.
#
#  2. resolve_origin_host - decide the origin, and who owns the setting. When ORIGIN_HOST
#     is set it is authoritative and gets written into frontcache.properties. When it is
#     NOT set, the properties file is left exactly as the operator wrote it and is only
#     read. That is what makes a hand-managed conf mount safe: nothing rewrites it behind
#     the operator's back.

# seed_conf <defaults-dir> <live-conf-dir>
seed_conf() {
  local defaults="$1" target="$2"
  local seeded=0 kept=0 name

  if [ ! -d "$defaults" ]; then
    echo >&2 "ERROR: config defaults missing from the image: $defaults"
    return 1
  fi

  mkdir -p "$target"
  for src in "$defaults"/*; do
    [ -e "$src" ] || continue
    name="$(basename "$src")"
    if [ -e "$target/$name" ]; then
      kept=$((kept + 1))
    else
      cp -a "$src" "$target/$name"
      echo "    + seeded $name"
      seeded=$((seeded + 1))
    fi
  done

  echo ">>> Config in $target: $kept kept, $seeded seeded from image defaults"
}

# resolve_origin_host <frontcache.properties path>
# Sets the global ORIGIN_HOST. Writes the file only when ORIGIN_HOST was already set.
resolve_origin_host() {
  local props="$1"

  if [ ! -f "$props" ]; then
    echo >&2 "ERROR: $props not found - config seeding must run first"
    return 1
  fi

  if [ -n "${ORIGIN_HOST:-}" ]; then
    echo ">>> ORIGIN_HOST=$ORIGIN_HOST is set - writing it into $(basename "$props")"
    if grep -qE '^[[:space:]]*front-cache\.origin-host[[:space:]]*=' "$props"; then
      sed -i -E "s|^[[:space:]]*front-cache\.origin-host[[:space:]]*=.*|front-cache.origin-host=${ORIGIN_HOST}|" "$props"
    else
      printf '\nfront-cache.origin-host=%s\n' "$ORIGIN_HOST" >> "$props"
    fi
    return 0
  fi

  # not set: the file is the source of truth, read-only as far as we are concerned
  ORIGIN_HOST="$(sed -n -E 's|^[[:space:]]*front-cache\.origin-host[[:space:]]*=[[:space:]]*([^[:space:]]+).*|\1|p' "$props" | tail -1)"
  if [ -n "$ORIGIN_HOST" ]; then
    echo ">>> ORIGIN_HOST not set - using front-cache.origin-host=$ORIGIN_HOST from $(basename "$props") (file left untouched)"
  else
    echo ">>> ORIGIN_HOST not set and front-cache.origin-host is absent from $(basename "$props")"
  fi
  return 0
}
