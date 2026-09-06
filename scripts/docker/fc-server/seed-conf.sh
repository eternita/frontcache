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
#     A RENAMED file breaks that rule's assumption, which is why RENAMED_CONF exists. To
#     the seeder a renamed file looks brand new, so it would copy the image's DEFAULTS in
#     under the new name and leave the operator's tuned file sitting next to it under the
#     old one - and Frontcache resolves the new name first. The operator's tuning would be
#     silently replaced by shipped defaults on upgrade. Skipping the seed instead leaves
#     their file authoritative; Frontcache reads the legacy name and warns until they
#     rename it themselves. We deliberately do NOT rename it for them: this directory can
#     be a hand-managed mount, and the rest of this script only ever writes to it when the
#     operator asked for it via ORIGIN_HOST.
#
#  2. resolve_origin_host - decide the origin, and who owns the setting. When ORIGIN_HOST
#     is set it is authoritative and gets written into frontcache.properties. When it is
#     NOT set, the properties file is left exactly as the operator wrote it and is only
#     read. That is what makes a hand-managed conf mount safe: nothing rewrites it behind
#     the operator's back.

# legacy_conf_name <current-name> -> echoes that file's pre-rename name, or nothing.
#
# A renamed file used to be handled by NOT seeding the new name and letting the operator's
# pre-rename file stay authoritative, because Frontcache read the old name as a fallback. 2.9.0
# removed that fallback, so the file is renamed in the mount instead: leaving it alone would mean
# a container that starts cleanly and runs every circuit breaker, timeout and bulkhead at its
# BUILT-IN default while the operator's tuned file sits unread beside it.
#
# Renaming a file in a mount the operator owns is more than this script otherwise does, and it is
# the lesser of the two - the content is untouched, and the alternative is a silent production
# change. Nothing else here rewrites a mounted file's contents.
#
# One line per renamed config file. Drop a line once nobody could still be upgrading from a
# release that shipped the old name.
legacy_conf_name() {
  case "$1" in
    resilience.properties) echo hystrix.properties ;;   # renamed in 2.7, old name unread since 2.9.0
  esac
}

# seed_conf <defaults-dir> <live-conf-dir>
seed_conf() {
  local defaults="$1" target="$2"
  local seeded=0 kept=0 skipped=0 name legacy

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
      continue
    fi

    legacy="$(legacy_conf_name "$name")"
    if [ -n "$legacy" ] && [ -e "$target/$legacy" ]; then
      mv "$target/$legacy" "$target/$name"
      echo "    > RENAMED $legacy to $name - the old name is no longer read as of 2.9.0, so your"
      echo "      settings would have stopped applying. The file's contents are untouched; the"
      echo "      hystrix.* keys inside it are still supported."
      skipped=$((skipped + 1))
      continue
    fi

    cp -a "$src" "$target/$name"
    echo "    + seeded $name"
    seeded=$((seeded + 1))
  done

  echo ">>> Config in $target: $kept kept, $seeded seeded from image defaults, $skipped moved onto their current name"
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
