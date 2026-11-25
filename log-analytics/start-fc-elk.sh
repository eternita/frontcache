#!/usr/bin/env bash
#
# Start the fc-elk stack (Elasticsearch + Kibana + Logstash).
#
# Config comes from .env beside this script (copy .env.example first).
# After it is up, pull logs with ./pull-logs.sh and open Kibana on :5601.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ES_PORT="${ES_PORT:-9200}"
KIBANA_PORT="${KIBANA_PORT:-5601}"
( cd "$SCRIPT_DIR" && docker compose up -d )

# Apply the Frontcache index templates (maps geoip.location -> geo_point, numerics,
# keyword enums) before any logs are indexed. Wait for ES to answer first.
echo ">>> Waiting for Elasticsearch on :$ES_PORT ..."
for i in $(seq 1 60); do
  if curl -sf "http://localhost:$ES_PORT/_cluster/health" >/dev/null 2>&1; then break; fi
  sleep 2
done
for tpl in frontcache frontcache-errors frontcache-fallbacks frontcache-rejected; do
  echo ">>> Applying $tpl index template ..."
  # Capture body + HTTP status separately (don't use -f: it hides the response
  # body, which is where ES explains a rejection, e.g. an index-pattern/priority
  # overlap between templates).
  resp="$(curl -s -w $'\n%{http_code}' -X PUT "http://localhost:$ES_PORT/_index_template/$tpl" \
    -H 'Content-Type: application/json' \
    --data-binary "@$SCRIPT_DIR/elasticsearch/${tpl}-index-template.json")"
  code="${resp##*$'\n'}"
  body="${resp%$'\n'*}"
  if [ "$code" = "200" ]; then
    echo "    template applied."
  else
    echo "    WARNING: $tpl template not applied (HTTP $code): $body"
  fi
done

# Import the data views + dashboards (Overview, Errors, Fallbacks, Rejected
# Requests) into Kibana.
# Idempotent (overwrite=true), so it re-imports on every start — this is what
# restores the dashboards after a `stop-fc-elk.sh -v` (which drops the ES volume
# where Kibana stores saved objects). Wait for Kibana's API to come up first.
echo ">>> Waiting for Kibana on :$KIBANA_PORT ..."
for i in $(seq 1 90); do
  if curl -sf "http://localhost:$KIBANA_PORT/api/status" >/dev/null 2>&1; then break; fi
  sleep 2
done
for dash in fc-dashboard fc-errors-dashboard fc-fallbacks-dashboard fc-rejected-dashboard; do
  echo ">>> Importing Kibana dashboard ($dash) ..."
  curl -sf -X POST "http://localhost:$KIBANA_PORT/api/saved_objects/_import?createNewCopies=false&overwrite=true" \
    -H 'kbn-xsrf: true' --form file=@"$SCRIPT_DIR/kibana/${dash}.ndjson" >/dev/null \
    && echo "    $dash imported." || echo "    WARNING: $dash import failed (is Kibana ready?)."
done

echo ">>> fc-elk is up."
echo "    Dashboards:"
echo "      Overview:  http://localhost:${KIBANA_PORT}/app/dashboards#/view/fc-overview"
echo "      Errors:    http://localhost:${KIBANA_PORT}/app/dashboards#/view/fc-errors"
echo "      Fallbacks: http://localhost:${KIBANA_PORT}/app/dashboards#/view/fc-fallbacks"
echo "      Rejected:  http://localhost:${KIBANA_PORT}/app/dashboards#/view/fc-rejected"
echo "    Elasticsearch: http://localhost:${ES_PORT}"
echo
echo "    Pull logs from your FC hosts, then refresh the dashboards:"
echo "      ./pull-logs.sh \"fc-us fc-eu\""
