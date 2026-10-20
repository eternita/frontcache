#!/usr/bin/env bash
#
# Check what each module's maven-metadata.xml says, at the ORIGIN and through the CDN.
#
# repo.eternita.co is CloudFront in front of the S3 bucket, and maven-metadata.xml is cached
# there for about a day. So the public URL can report the PREVIOUS release for hours after a
# perfectly good publish - which looks exactly like a failed publish. That happened at 2.6.0:
# frontcache-core read as 2.5.0 through the CDN while S3 held 2.6.0 the whole time.
#
# This prints both, so the difference is obvious rather than alarming. The S3 column is the
# authoritative one - it is what `./gradlew publish` actually wrote.
#
#   ./scripts/dist/check-published-metadata.sh          # all modules
#   ./scripts/dist/check-published-metadata.sh 2.6.0    # ... and flag anything not at 2.6.0
#
# Needs AWS credentials for the S3 column (the same ones `./gradlew publish` uses). Without
# them the S3 column reads "?" and only the CDN column is shown.
set -uo pipefail

EXPECT="${1:-}"
BUCKET="s3://repo.eternita.co/maven2/org/frontcache"
BASE="https://repo.eternita.co/maven2/org/frontcache"
MODULES="frontcache-core frontcache-agent frontcache-server frontcache-console"

release_of() { grep -o '<release>[^<]*</release>' | sed 's/<[^>]*>//g'; }

printf '%-22s %-10s %-10s %-10s %s\n' MODULE S3 CDN CDN-AGE ''
STALE=0 WRONG=0

for m in $MODULES; do
  s3="$(aws s3 cp "$BUCKET/$m/maven-metadata.xml" - 2>/dev/null | release_of)"
  [ -n "$s3" ] || s3="?"
  cdn="$(curl -fsSL "$BASE/$m/maven-metadata.xml" 2>/dev/null | release_of)"
  [ -n "$cdn" ] || cdn="?"
  age="$(curl -fsSI "$BASE/$m/maven-metadata.xml" 2>/dev/null | awk 'tolower($1)=="age:"{print $2}' | tr -d '\r')"
  [ -n "$age" ] || age="-"

  note=""
  [ "$s3" != "?" ] && [ "$s3" != "$cdn" ] && { note="CDN stale (origin is $s3)"; STALE=$((STALE+1)); }
  if [ -n "$EXPECT" ] && [ "$s3" != "?" ] && [ "$s3" != "$EXPECT" ]; then
    note="${note:+$note; }ORIGIN IS NOT $EXPECT"; WRONG=$((WRONG+1))
  fi
  printf '%-22s %-10s %-10s %-10s %s\n' "$m" "$s3" "$cdn" "$age" "$note"
done

echo
if [ "$WRONG" -gt 0 ]; then
  echo "$WRONG module(s) did not publish $EXPECT - this is a real problem, re-run ./gradlew publish"
  exit 1
fi
if [ "$STALE" -gt 0 ]; then
  cat <<'NOTE'
Some modules are stale in the CDN only. The publish is fine. The entry expires on its own
(age approaching 86400), or flush it with CloudFront credentials - which the S3 publishing
user does not have:

  aws cloudfront create-invalidation --distribution-id <ID> \
    --paths '/maven2/org/frontcache/*/maven-metadata.xml'
NOTE
fi
exit 0
