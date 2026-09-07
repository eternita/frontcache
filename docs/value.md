# Frontcache — Value Proposition

Concepts: [concept.md](concept.md) · Topologies: [deployment-usecases.md](deployment-usecases.md)

## 1. The claim, in one paragraph

Frontcache takes the page assembly work your app does over and over and does it once. On a
replay of **100,000 real production requests** against a live app, turning the cache on cut the
**origin's work by 98.7%** (79,900 renders → 1,003), dropped **median response time from 41.3 ms
to 1.6 ms**, and raised **throughput 6.1×** at the same concurrency — serving byte-identical
content. The app was not changed, tuned, or rewritten; only the cache processor was swapped.

## 2. What was measured

A straight A/B against the same running app, same request stream, same machine, back to back.
Only one property differed between the two runs:

| | Run A — cache on | Run B — cache off |
|---|---|---|
| `front-cache.cache-processor.impl` | `L1L2CacheProcessor` | `NoopCacheProcessor` |
| Everything else | identical | identical |

Because `NoopCacheProcessor` keeps Frontcache in the request path — the proxy hop, `fc:include`
stitching and the resilience command wrappers all still run — **run B is not "no Frontcache". It is
Frontcache with the cache disabled.** That makes this a measurement of what the *cache* is worth,
with every other cost paid by both sides.

**Load:**

```bash
node load-test.js -t 8 -n 1000 --loop 100 --target http://dev.hobbyray.com:8080
```

- 8 parallel workers, one request in flight each — exactly 8 concurrent requests, both runs.
- 1,000 requests per pass × 100 passes = **100,000 requests**.
- The requests are replayed from a real Frontcache request log
  (`benchmark/requests/requests.csv`), in recorded order, duplicates kept — so the hit
  distribution is production's, not synthetic.
- Recorded host is sent as the `Host` header, so per-domain config resolves the way it does in
  production.
- Both runs transferred **7.26 GB** — the same bytes. Same content, different cost.

Harness: [`benchmark/`](../benchmark) — [`extract-requests.js`](../benchmark/extract-requests.js)
turns a request log into a replayable CSV, [`load-test.js`](../benchmark/load-test.js) replays it
and saves every report. Both are dependency-free Node (18+). See
[benchmark/README.md](../benchmark/README.md).

## 3. Results

| Metric | Cache off | Cache on | Change |
|---|---:|---:|---|
| **Total runtime** | 580.1 s | **94.9 s** | **6.1× faster** (8 min saved) |
| **Throughput** | 172.4 req/s | **1054.0 req/s** | **6.1×** |
| **Bandwidth served** | 12.81 MB/s | **78.35 MB/s** | **6.1×** |
| Latency avg | 46.4 ms | **7.6 ms** | −84% |
| Latency **p50** | 41.3 ms | **1.6 ms** | **−96%** (25.8× lower) |
| Latency p90 | 92.3 ms | **9.3 ms** | −90% |
| Latency p95 | 151.2 ms | **15.7 ms** | −90% |
| Latency p99 | 208.1 ms | **120.3 ms** | −42% |
| Latency max | 295.2 ms | 775.4 ms | *worse — see §6* |
| **Requests reaching origin** | 79,900 | **1,003** | **−98.7%** (79.7× fewer) |
| Cache hit ratio | — | **98.74%** | 78,896 from-cache / 79,899 |
| Requests OK / failed | 99,800 / 200 | 99,799 / 201 | same (recorded 404s + one 503) |

Median latency, to scale:

```
cache off  ████████████████████████████████████████████  41.3 ms
cache on   ██                                             1.6 ms
```

Origin renders per 100k requests:

```
cache off  ████████████████████████████████████████████  79,900
cache on   █                                              1,003
```

## 4. What that buys you

**1 — Pages get fast, not just faster.** A 1.6 ms median is not an optimized origin; it is a
cache hit. Half of all traffic never touches application code, a database, or a template engine.
The tail improves too: p95 goes from 151 ms to 16 ms, which is the number users actually feel.

**2 — The origin stops being the bottleneck.** 1,003 origin renders instead of 79,900 means the
app tier serves **1.3% of the traffic it served before**. Everything downstream of it — the
database, the search index, the third-party APIs your page calls — sees the same 98.7% cut. This
is the number that decides whether a traffic spike is an incident or a non-event.

**3 — Capacity you already paid for.** 6.1× throughput with zero application changes and no extra
app instances. Read the other direction: the same traffic can be served by roughly a sixth of the
origin capacity. On metered infrastructure that is the line item; on fixed infrastructure it is
the headroom you stop having to buy ahead of.


## 5. Where the speedup comes from

Nothing here is generic HTTP caching — it is the four mechanisms described in
[concept.md](concept.md), doing what they are for:

- **L1 + L2 (`L1L2CacheProcessor`)** — hot fragments answered from Ehcache in memory, the long
  tail from the on-disk Lucene index. The 1.6 ms p50 is L1; the tail below p90 is L2.
- **The origin declares cacheability.** `x-frontcache-component-maxage` is set by the app, which
  is why a 98.7% hit ratio is achievable without a hand-maintained URL rule list.
- **Fragment granularity.** Pages are cached in pieces and stitched, so one dynamic fragment does
  not force a full re-render — that is why the 1,003 origin calls stay 1,003 instead of dragging
  whole pages with them.

## 6. How to read these numbers honestly

- **The max got worse, and the p99 gained least — both expected.** Maximum latency went from
  295.2 ms to 775.4 ms, and p99 fell only 42% (208.1 → 120.3 ms) where p50 fell 96%. With the
  cache on, the run pushes 6.1× more requests through in the same window, so the ~1.3% of
  requests that still reach the origin queue behind far more concurrency — plus L2 (Lucene)
  reads and one tripped circuit breaker (a single `503`). The trade is real: near-everything
  gets much faster, the small uncached remainder gets a worse worst case. If your SLO is a hard
  max rather than a percentile, size the origin for the uncached fraction.
- **98.74% is this working set's hit ratio, not a universal one.** 1,000 URLs replayed 100 times
  is a hot set by construction. Over the full 681k-request log the same harness measures ~66.7%
  from-cache (see the sample report in [benchmark/README.md](../benchmark/README.md)). Expect a number between the two, shaped by your TTLs and traffic shape — the
  *origin offload* scales with whatever ratio you actually get.
- **8 concurrent workers cannot saturate the cached path.** At 1054 req/s the cache-on run was
  latency-bound, not capacity-bound; the cache-off run was origin-bound. So 6.1× is the gain
  *at fixed concurrency* — it is a floor on the throughput headroom, not a measured ceiling.
- **Anonymous traffic only.** The replay carries no cookies or auth headers, which is right for
  cache benchmarking and says nothing about logged-in paths.
- **One app, one shape of page.** These are hobbyray.com's pages. The mechanism generalizes; the
  exact multiplier does not.

## 7. Raw reports

Verbatim, as saved by the harness in [`benchmark/results/`](../benchmark/results).

**Cache on** 

```
total runtime     1m 34.9s (94.9s)
threads           8
target            http://dev.hobbyray.com:8080
limit             1000 per pass
loop              100 passes
----------------------------------------------------------
requests          100000
  ok              99799
  failed          201
throughput        1054.0 req/s
transferred       7.26 GB (78.35 MB/s)
----------------------------------------------------------
latency (ms)
  avg             7.6
  min / max       0.2 / 775.4
  p50 / p90       1.6 / 9.3
  p95 / p99       15.7 / 120.3
----------------------------------------------------------
status codes
  200             80699 (80.7%)
  302             19100 (19.1%)
  404             200 (0.2%)
  503             1 (0.0%)
----------------------------------------------------------
frontcache disposition
  from-cache      78896 (78.9%)
  dynamic         1003 (1.0%)
```

**Cache off** 

```
total runtime     9m 40.1s (580.1s)
threads           8
target            http://dev.hobbyray.com:8080
limit             1000 per pass
loop              100 passes
----------------------------------------------------------
requests          100000
  ok              99800
  failed          200
throughput        172.4 req/s
transferred       7.26 GB (12.81 MB/s)
----------------------------------------------------------
latency (ms)
  avg             46.4
  min / max       0.3 / 295.2
  p50 / p90       41.3 / 92.3
  p95 / p99       151.2 / 208.1
----------------------------------------------------------
status codes
  200             80700 (80.7%)
  302             19100 (19.1%)
  404             200 (0.2%)
----------------------------------------------------------
frontcache disposition
  dynamic         79900 (79.9%)
```

The `302` responses (19.1%) are redirects that pass through un-cached in both runs, and the `404`s
are recorded in the source log — both appear identically on each side, so they do not move the
comparison.
