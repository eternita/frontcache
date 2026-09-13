# Plan — rework the **Frontcache Overview** dashboard

> **Status: implemented** on branch `elk-updates`. `kibana/fc-dashboard.ndjson` and
> [README.md](README.md) are updated, the dashboard is re-imported into the running stack, the eleven
> orphans are deleted, and every panel was verified against Elasticsearch (§8). The only step not
> run is the clean-slate `./stop-fc-elk.sh -v` — it drops the ES volume and would discard the
> ~4.5M ingested documents.

Scope: `examples/log-analytics/kibana/fc-dashboard.ndjson` (saved-object export) plus the
"Frontcache Overview" bullet in [README.md](README.md). No changes to Logstash, the index
templates, or the other three dashboards.

---

## 1. Why this is a file edit, not a Kibana edit

`start-fc-elk.sh` imports `kibana/*.ndjson` with
`POST /api/saved_objects/_import?createNewCopies=false&overwrite=true`, so the **ndjson in git is
the source of truth**. Editing in the Kibana UI and not re-exporting gets silently reverted on the
next `./start-fc-elk.sh`.

Import with `overwrite=true` **updates and adds, it never deletes**. Anyone who already ran the
current dashboard keeps the eleven dropped `lens` objects as orphans in their Kibana. Step 6 covers
cleaning them up.

## 2. Field reference (from the running data)

Parsed by `logstash/pipeline/fc-logstash.conf`, mapped as `keyword` by
`elasticsearch/frontcache-index-template.json`:

| field | values seen in `logs/*frontcache-requests*.log` | count in the sample |
| --- | --- | --- |
| `request_type` | `toplevel` | 1,353,699 |
| | `include` | 2,624,256 |
| | `include-async` | 16,241 |
| `is_cacheable` | `cacheable` / `direct` | 3,993,740 / 456 |
| `is_cached` | `from-cache` / `dynamic` | 3,116,878 / 877,318 |
| `hystrix_error` | `success` / `error` | 3,993,849 / 347 |
| `runtime_millis` | `long`, milliseconds | — |

`is_cached:dynamic` means **the request went to origin**, for every request type. That is the
definition behind the origin-hits tile. Note `is_cacheable:direct` requests (non-cacheable by
contract) are always `dynamic` — they are origin hits, but not cache *misses*.

## 3. Defect to fix in the same pass — the data view is too wide

`frontcache-data-view` has `title: "frontcache-*"`, which matches not only the request index but
also `frontcache-errors-*`, `frontcache-fallbacks-*` and `frontcache-rejected-*`. Against the
current data that is 4,555,599 documents where the request log has 3,994,196 — every unfiltered
Overview panel is inflated by the 560,438 rejected-request docs.

It bites hardest on the new error tile: `fc-logstash-rejected.conf` parses the same
`hystrix_error` field and every rejected doc carries `error`, so a naive
`count(kql='hystrix_error:"error"')` reads **560,785** instead of the true **347**. (The removed
"Error rate" tile had exactly this bug — it showed ~12.3% instead of ~0.009%.)

**Fix:** narrow the data view title to

```
frontcache-*,-frontcache-errors-*,-frontcache-fallbacks-*,-frontcache-rejected-*
```

Verified against the running cluster: `_count` drops from 4,555,599 to 3,994,196 and the
`hystrix_error:"error"` count from 560,785 to 347. This is safe — `frontcache-data-view` is
referenced only by the Overview; the other three dashboards ship their own
(`fc-errors-data-view`, `fc-fallbacks-data-view`, `fc-rejected-data-view`). It also silently
corrects the retained panels (requests-by-node, top-URL tables, bandwidth).

Two KPI tiles do need to see across the boundary, so the dashboard ships a **second** data view,
`fc-client-data-view`, titled `frontcache-*,-frontcache-errors-*,-frontcache-fallbacks-*` —
request index plus rejected index, nothing else. Only `fc-all-client-requests` and
`fc-guarded-total` use it (§5.1); every other panel stays on the narrow view.

## 4. Panels removed

| id | title | slot today |
| --- | --- | --- |
| `fc-total-requests` | Total requests | KPI row |
| `fc-cache-hit-ratio` | Cache hit ratio (toplevel) | KPI row |
| `fc-median-latency` | Median latency | KPI row |
| `fc-p95-latency` | P95 latency | KPI row |
| `fc-error-rate` | Error rate | KPI row |
| `fc-bot-share` | Bot share | KPI row |
| `fc-latency-bot-browser` | Median latency: bot vs browser | breakdowns |
| `fc-requests-by-domain` | Requests by domain | breakdowns |
| `fc-cacheable-pie` | Cacheable vs direct | breakdowns |
| `fc-client-type-pie` | Bot vs browser | breakdowns |
| `fc-cache-status-pie` | Cache status | breakdowns |

Delete the whole ndjson line for each, its `references` entry, and its `panelsJSON` entry.
`browserBot` and `domain` stay on the index and in Discover; only the Overview panels go. The
overall `Cache status` pie is redundant once the per-type splits land — it was dominated by
includes (2.6M of 4M docs), so it read as an include statistic wearing a global label.

## 5. Panels added

Ten `lens` saved objects, all on the existing `frontcache-data-view` reference
(`{"id":"frontcache-data-view","name":"indexpattern-datasource-layer-layer1","type":"index-pattern"}`).
`fc-total-requests` is the skeleton for the count tiles, `fc-cacheable-pie` for the pies.

### 5.1 KPI row — seven `lnsMetric` tiles

Every tile is a **single column with a per-column `filter`** — no Lens formula anywhere. A
`filter: {"language":"kuery","query":"…"}` is valid on any metric column, which keeps the saved
state small and hand-reviewable instead of the four-column `tinymathAst` chain a formula expands
to.

| new id | label | filter | data view |
| --- | --- | --- | --- |
| `fc-all-client-requests` | All client requests | `request_type:"toplevel" or reject_reason:*` | `fc-client-data-view` |
| `fc-guarded-total` | Total guarded requests | `reject_reason:*` | `fc-client-data-view` |
| `fc-total-toplevel` | Toplevel requests | `request_type:"toplevel"` | `frontcache-data-view` |
| `fc-total-include` | Include requests | `request_type:"include"` | `frontcache-data-view` |
| `fc-total-include-async` | Include-async requests | `request_type:"include-async"` | `frontcache-data-view` |
| `fc-total-origin-hits` | Origin hits | `is_cached:"dynamic"` | `frontcache-data-view` |
| `fc-total-errors` | Errors | `hystrix_error:"error"` | `frontcache-data-view` |

All seven are `operationType: "count"` on `___records___`.

`include` and `include-async` are **exact-match, mutually exclusive** filters, so the three count
tiles partition the request index and `toplevel + include + include-async` equals its total doc
count.

Format every count as `{"id":"number","params":{"decimals":0}}` and set `customLabel: true` so the
labels above survive.

`reject_reason` is the discriminator between the two indices — it is present on 100% of rejected
documents and 0% of request documents (verified: 0 either way). That makes
`request_type:"toplevel" or reject_reason:*` sum to exactly
`fc-total-toplevel + fc-guarded-total` (1,353,699 + 560,438 = 1,914,137), so the three leftmost
tiles visibly add up instead of leaving the reader to wonder about a rounding gap.

The two populations barely overlap: a guard rule acts *before* cache or origin, so a rejected
request gets no request-log line. The only documents in both indices are the 347 resilience events
(`failure`, `short-circuited`, `rejected`, `timeout`) — which are also, exactly, the Errors tile.
`fc-all-client-requests` therefore double-counts 116 toplevel requests out of 1.9M (0.006%); the
alternative, deduplicating, would make the tiles stop adding up for no visible gain.

Give the seven KPI panels `"hidePanelTitles": true` in their dashboard `embeddableConfig`. An
`lnsMetric` renders its own column label inside the tile, so the panel title is a verbatim
duplicate stacked directly above it.

### 5.2 Three new charts

| new id | title | config |
| --- | --- | --- |
| `fc-type-split-pie` | Toplevel vs include | `lnsPie`, terms on `request_type`, `size: 5`, metric `count`, `numberDisplay: "percent"` |
| `fc-cache-split-toplevel` | Cache hit vs miss (toplevel) | `lnsPie`, terms on `is_cached`, `size: 3`, layer query `request_type:"toplevel"` |
| `fc-cache-split-include` | Cache hit vs miss (include) | same, layer query `request_type:"include"` |

Scope the two cache-split pies with the saved object's top-level
`"query": {"language":"kuery","query":"request_type:\"toplevel\""}` rather than a column filter —
it applies to every column in the layer and surfaces in the Lens editor as the layer query, so the
next person editing it can see the scope.

`size: 5` on the type split so `include-async` gets its own slice; `otherBucket: false` (as the
existing pies do) so nothing is hidden behind "Other".

**Miss semantics:** these pies split on `is_cached`, so a non-cacheable (`is_cacheable:"direct"`)
toplevel lands in the `dynamic` slice — 456 requests in the current sample, ~0.03%. That matches
how the retained `fc-cache-ratio-time` counts, so leave it. For a strict hit/miss reading, add
`and is_cacheable:"cacheable"` to the layer query and say so in the title.

## 5.3 Panel modified

`fc-requests-by-node` is retitled **Toplevel requests by FC node** and scoped with the layer query
`request_type:"toplevel"` (metric label `Toplevel requests`). Unfiltered it counted every include
too, so it measured fan-out per node rather than pages served: 2,980,319 / 1,013,877 becomes
994,361 / 359,338 for `fc-eu` / `fc-ap`.

## 6. Resulting layout

48-column grid. Rows below `y=7` keep their `y` because the KPI row keeps `h: 7`; only the KPI row
and the three breakdown rows change.

| y | h | panels (x, w) |
| --- | --- | --- |
| 0 | 7 | *(panel titles hidden — see §5.1)* `fc-all-client-requests` (0,7) · `fc-guarded-total` (7,7) · `fc-total-toplevel` (14,7) · `fc-total-include` (21,7) · `fc-total-include-async` (28,7) · `fc-total-origin-hits` (35,7) · `fc-total-errors` (42,6) |
| 7 | 12 | `fc-request-volume` (0,24) · `fc-cache-ratio-time` (24,24) — unchanged |
| 19 | 12 | `fc-latency-percentiles` (0,48) — unchanged |
| 31 | 12 | `fc-latency-cache-origin` (0,**24**) · `fc-bandwidth-time` (**24**,**24**) — widened to close the hole left by `fc-latency-bot-browser` |
| 43 | 14 | **`fc-type-split-pie`** (0,16) · **`fc-cache-split-toplevel`** (16,16) · **`fc-cache-split-include`** (32,16) — the three splits read as one row |
| 57 | 14 | `fc-requests-by-node` (0,24) · `fc-top-countries` (24,24) — widened to close the hole left by `fc-cache-status-pie` |
| 71 | 16 | `fc-top-slow-urls` (0,24) · `fc-top-hot-urls` (24,24) — unchanged |

Seven KPI tiles do not divide 48 evenly; `7×6 + 6` is the least-lopsided split, with the narrow
one under the smallest number (errors). Panel count goes 20 → 19 (eleven out, ten in).

Renumber `panelIndex` / `i` / `panelRefName` as `1..20` in row order and keep `references[].name`
in lockstep — a mismatch between `panelRefName` and `references[].name` renders the panel as a
broken embeddable.

## 7. Implementation steps

1. Back up: `cp kibana/fc-dashboard.ndjson /tmp/fc-dashboard.ndjson.bak`.
2. Rewrite `kibana/fc-dashboard.ndjson`: narrow the data view title (§3), drop the ten objects
   (§4), append the ten new ones (§5), rescope `fc-requests-by-node` (§5.3), rebuild `panelsJSON` + `references` to §6. Keep one object
   per line and the existing object order (data view → lens in panel order → dashboard) so the
   diff stays reviewable. The dashboard object must stay `id: fc-overview` — the README,
   `start-fc-elk.sh` and the deep link all hard-code it.
3. Re-import into the running stack:
   ```
   curl -sf -X POST "http://localhost:5601/api/saved_objects/_import?createNewCopies=false&overwrite=true" \
     -H 'kbn-xsrf: true' --form file=@kibana/fc-dashboard.ndjson
   ```
4. Delete the ten orphans from any Kibana that imported the old version (fresh installs are
   unaffected):
   ```
   for id in fc-total-requests fc-cache-hit-ratio fc-median-latency fc-p95-latency \
             fc-error-rate fc-bot-share fc-latency-bot-browser fc-requests-by-domain \
             fc-cacheable-pie fc-client-type-pie fc-cache-status-pie; do
     curl -s -X DELETE "http://localhost:5601/api/saved_objects/lens/$id" -H 'kbn-xsrf: true' >/dev/null
   done
   ```
   Leave this out of `start-fc-elk.sh` — it is a one-time migration, and the script should stay
   import-idempotent. Mention it in the README instead if stale panels confuse anyone.

## 8. Verification

Open `http://localhost:5601/app/dashboards#/view/fc-overview` with the time picker wide enough to
cover the pulled logs, then cross-check against Elasticsearch:

```
curl -s 'http://localhost:9200/frontcache-*,-frontcache-errors-*,-frontcache-fallbacks-*,-frontcache-rejected-*/_search' \
  -H 'content-type: application/json' -d '{
  "size": 0,
  "aggs": {
    "by_type": { "terms": { "field": "request_type" } },
    "origin":  { "filter": { "term": { "is_cached": "dynamic" } } },
    "errors":  { "filter": { "term": { "hystrix_error": "error" } } }
  }
}' | python3 -m json.tool
```

And the two client-side tiles, on the wider view (§3):

```
V='frontcache-*,-frontcache-errors-*,-frontcache-fallbacks-*'
curl -s "http://localhost:9200/$V/_count" -H 'content-type: application/json' \
  -d '{"query":{"exists":{"field":"reject_reason"}}}'                        # 560438
curl -s "http://localhost:9200/$V/_count" -H 'content-type: application/json' \
  -d '{"query":{"bool":{"should":[{"term":{"request_type":"toplevel"}},
       {"exists":{"field":"reject_reason"}}],"minimum_should_match":1}}}'    # 1914137
```

Checklist:

- The three request-type tiles sum exactly to the request index doc count (the three
  `request_type` values partition it).
- Errors tile reads **347**, not 560,785 — that is the §3 fix working.
- By-node bars read 994,361 / 359,338, not the all-request 2,980,319 / 1,013,877 (§5.3).
- Origin-hits tile equals the `dynamic` doc count, and is ≥ the `dynamic` slices of the two new
  cache pies summed (the difference is `include-async`).
- All client requests = 1,914,137 and is the exact sum of the guarded and toplevel tiles beside
  it. If it is not, `reject_reason` has leaked into the request index (or the second data view is
  matching `frontcache-errors-*` / `-fallbacks-*`).
- Every panel renders — no "could not locate that index-pattern", no empty embeddable frames.
- Hard-refresh or use a private window; Kibana caches saved-object state per session and a stale
  tab can still show deleted panels.

Then prove the file works from scratch: `./stop-fc-elk.sh -v && ./start-fc-elk.sh`.

## 9. Docs updated in the same commit

- [README.md](README.md) §"The dashboards" → "Frontcache Overview": rewrite the **KPI tiles**
  bullet to the seven new tiles and the **Breakdowns** bullet to drop bot-vs-browser latency,
  by-domain and the cacheable / client-type pies, adding the type split and the two cache splits.
  Errors, Fallbacks and Rejected sections unchanged.
- The root [README.md](../../README.md) row for log-analytics is topic-level and needs no change.
  No version strings are involved, so the twelve-file version-bump rule does not apply.

## 10. Decisions (answered)

1. **`fc-latency-bot-browser`** — removed along with the bot-vs-browser pie; `y=31` rebalanced to
   two 24-wide panels.
2. **Error tile** — kept as **Total error count** (`hystrix_error:"error"`), an absolute count
   rather than the old rate, and correct only because of the §3 data-view fix.
3. **`include-async`** — its own KPI tile, its own pie slice, and excluded from the `include`
   count tile.
4. **P95 latency tiles** — dropped from the KPI row; `Latency percentiles over time` still carries
   p50/p90/p95/p99 as a chart.
5. **Client-side totals** — `All client requests` and `Total guarded requests` prepended
   to the row, on the second data view (§3).
