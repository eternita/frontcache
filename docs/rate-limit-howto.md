# Rate limiting one loud client — a how-to

A walk from "we are being hammered by one address" to a rule that stops it, with the
request-by-request behaviour of each rule spelled out, and a five-minute version you can run
on your laptop (§5).

Per-IP rate limiting shipped in **2.8.0** and everything here is current as of 2.9.0. It is a
single condition — `rate:100/10s` — usable in any guard rule, so nothing else about guard
rules changes. [guard-getting-started.md](guard-getting-started.md) is the reference for the
rest of the grammar; this page is the tutorial for this one predicate.

---

## 1. Why you would want this

A cache is a very good answer to *many people asking for the same thing*. It is no answer at
all to *one client asking too fast*, and the three shapes below are what is usually left after
the cache is working:

- **The scraper walking the catalogue.** 200 requests per second through every product page.
  Each one is a legitimate URL, so the cache serves most of them — but it is still your
  bandwidth, your threads, and your cache being churned by one client who is not going to buy
  anything. When it walks pages nobody else has requested, it is also a cache miss and an
  origin render every time.
- **The uncacheable path.** `/search?q=…` has a different query on every request, so every
  request is a unique cache key, a miss, and an origin call. One client in a loop here fills
  the origin thread pool while the rest of the site is served from cache and looks fine —
  right up until it isn't.
- **The credential-stuffing loop.** Thousands of POSTs to `/login.htm`. Every one of them is
  an origin round trip, a database lookup, and a failed login your application has to handle.

All three are *one address behaving unlike a person*. Nothing about the request tells you
that — the URI is valid, the method is valid, the user agent may well be a real browser
string. The only distinguishing feature is **how often it arrives**, which is exactly what the
`rate:` condition measures.

**Why at the edge.** The rule runs in the guard pipeline, which is **before the cache and
before the origin**. A refused request costs a hash and an array read: no cache lookup, no
origin connection, no include stitching, no log pipeline noise beyond one line. That is the
whole point of doing it here rather than in the application — by the time your app can say
"too fast", it has already paid for the request.

**What you get, stated honestly.** This stops *one loud client*. It is not a WAF, and against
ten thousand hosts a `100/10s` limit still permits a hundred thousand requests every ten
seconds. §9 is the full list of what it cannot do — read it before you rely on it for
anything.

---

## 2. The rule, in thirty seconds

```
ip-flood | rate:100/10s | reject:429 Too Many Requests | dry-run
```

`<name> | <condition> | <action> [| dry-run]`, like every guard rule. The new part is the
condition:

```
rate:<limit>/<window> [bucket=<name>] [scope=<toplevel|all>]
```

| Field | Meaning | Default |
| --- | --- | --- |
| `<limit>` | requests one IP may send inside a window | — |
| `<window>` | `<n>s`, `<n>m`, `<n>h`; a bare unit means 1, so `/s` is `/1s`. Max `1h` | — |
| `bucket=<name>` | counter namespace — rules naming the same bucket share one counter | the rule name |
| `scope=` | `toplevel` counts client requests only; `all` also counts `<fc:include>` fragments | `toplevel` |

It is true on the request that would be the **`limit`+1-th** inside the current window, and on
every further request until the window rolls over. The window is a fixed slot on a grid, not a
rolling period: the count resets at the boundary (§4.7).

Both numbers live in the rule, so changing a limit is an edit plus
`action=reload-guard-rules` — no restart.

---

## 3. Setup, step by step

### Step 1 — tell Frontcache whose address to believe (do this first)

**This is the one step that is silent when you get it wrong**, and getting it wrong takes the
site down with the first rule you enable.

Frontcache never reads `X-Forwarded-For` — or any other forwarding header — from a peer you
have not declared trusted. It cannot: those headers are client-supplied, so a client sending
its own would mint a fresh counter on every request and defeat the limiter completely, while
passing any test that did not try it.

So with no trusted proxies configured, the limiter keys on the **socket peer**. If anything
sits in front of Frontcache — nginx, a CDN, a load balancer, a sibling Frontcache node — that
peer is the proxy, and **every visitor on earth counts as one client**.

In `FRONTCACHE_HOME/conf/frontcache.properties`:

```properties
# whatever actually terminates the client connection: the local nginx, your CDN's egress
# ranges, sibling Frontcache nodes. Comma-separated CIDRs.
front-cache.client-ip.trusted-proxies=127.0.0.1/32,::1/128,10.0.0.0/8

# read only from a trusted peer, and walked RIGHT TO LEFT - the leftmost entry is whatever
# the client wrote; the rightmost non-trusted one is what the last proxy we trust observed
#front-cache.client-ip.header=X-Forwarded-For
```

**Properties are read at startup**, so this one needs a node restart — unlike the rules
themselves. Frontcache logs a `WARN` at startup and on every reload when a `rate:` rule is
loaded while this key is empty; it warns rather than refuses, because a guard misconfiguration
should degrade to "Frontcache as usual", not to an outage.

The one case where empty is correct is a node clients connect to **directly** — which is what
the laptop walkthrough in §5 uses.

This does not touch the client-IP column of your request logs: that is resolved separately and
is unchanged, so your ELK pipeline and saved Kibana searches are untouched. The two can
legitimately disagree, which is why a rate rejection records the address it actually counted
(§6).

### Step 2 — exempt your own infrastructure

Your health check, your console, your metrics scrape and your cache replication all address
the node from a handful of addresses, as fast as they like. Put the exemptions **above** the
rate rules, as always — an `allow` that matches first stops evaluation, so an exempt path never
touches a counter at all:

```
ping-by-ip      | uri~^/fc-ping\.jsp$        | allow
mgmt-by-ip      | uri~^/frontcache-io        | allow
fc-metrics      | uri~^/fc-metrics$          | allow
fc-dashboard    | uri~^/fc-dashboard\.stream | allow
hystrix-stream  | uri~^/hystrix\.stream      | allow
```

### Step 3 — add the rule, in dry-run

```
ip-flood | rate:100/10s | reject:429 Too Many Requests | dry-run
```

A dry-run rule is evaluated and logged, and **it does count** — that is the whole point, since
dry-run is how you size a limit before it refuses anybody.

### Step 4 — reload, no restart

```bash
curl -H "Authorization: Bearer YOUR_API_KEY" "http://<edge>/frontcache-io?action=reload-guard-rules"
```

### Step 5 — read what it would have caught

Wait a day of real traffic, then look at the dry-run hits (§6) and answer one question: *how
many real visitors would this have refused?* Size the limit from that number, not from
intuition (§7).

### Step 6 — let it act

Drop `| dry-run`, reload again. Rollback is the same edit in reverse — comment the line out and
reload; it takes effect immediately, and the counters of any rule you left alone keep their
counts.

---

## 4. Scenarios

### 4.1 The blanket brake — a scraper walking the catalogue

```
ip-flood | rate:100/10s | reject:429 Too Many Requests
```

One client at `203.0.113.7`, 10-second slots. `t` is the start of a slot:

| Time | What arrives | Count in this slot | What Frontcache does |
| --- | --- | --- | --- |
| `t+0.0s` … `t+6.1s` | requests 1–100 | 100 | served normally — cache hit, cache miss, whatever it would have been |
| `t+6.2s` | request 101 | 101 | **429**, `Retry-After: 4`, `Cache-Control: no-store` |
| `t+6.3s` … `t+9.9s` | 400 more | 501 | **429** each — none reaches cache or origin |
| `t+10.0s` | the slot rolls over | back to 0 | — |
| `t+10.1s` | next request | 1 | served normally |

`Retry-After` is the seconds left in the slot (at `t+6.2s` of a 10 s slot: 4), so a
well-behaved client backs off exactly as long as it needs to and no longer.

**The limit applies to what actually passes through this node.** Guard rules run before the
cache, so a request served from cache counts too — and if your CSS, JS and images are proxied
through Frontcache, one page view can be thirty requests. Size for the traffic the node sees,
not for page views (§7).

### 4.2 The expensive path that can never be cached

Limit the path that hurts, and leave the rest of the site alone:

```
search-flood | uri~^/search ; rate:20/10s | reject:429 Too Many Requests
```

The same visitor, browsing and searching in one session:

| # | Request | Matches `uri~^/search`? | `search-flood` count | Result |
| --- | --- | --- | --- | --- |
| 1 | `GET /en/catalog/shoes.htm` | no | 0 | 200 — the counter is never touched |
| 2 | `GET /search?q=boot` | yes | 1 | 200 |
| … | 19 more searches | yes | 20 | 200 |
| 22 | `GET /search?q=boots+10` | yes | 21 | **429**, `Retry-After` |
| 23 | `GET /en/catalog/boots.htm` | no | 21 | 200 — browsing still works |

This is the shape to reach for first: **a tight limit on one expensive path is safer than a
loose limit on everything**, because the blast radius of getting the number wrong is one
feature, not the site.

The ordering inside the condition is not something you have to think about: whatever order you
write it in, Frontcache evaluates `rate:` **last** in its rule, so the counter is only ever fed
by requests the rest of the condition already selected.

### 4.3 Credential stuffing on the login form

```
login-flood | uri~^/login\.htm$ ; method:POST ; rate:10/1m | reject:429 Slow down
```

| # | Request | Counted? | Result |
| --- | --- | --- | --- |
| 1 | `GET /login.htm` | no — the method does not match | 200, the form renders |
| 2–11 | `POST /login.htm` ×10 | yes | 200, your app answers each one |
| 12 | `POST /login.htm` | yes | **429**, `Retry-After` up to 60 |
| 13 | `GET /login.htm` | no | 200 — a real person can still load the page |

Ten attempts a minute is generous for a human and hopeless for a stuffing script. Two things
worth knowing before you tighten it further: an office behind one NAT address shares the
budget, and Frontcache never reads a cookie's *value*, so this cannot be scoped per account —
it is per address, and only per address.

### 4.4 The crawler you want to slow down, not block

```
bot-flood | client-type:bot ; rate:100/10s | reject:429 Crawl slower
```

`client-type` comes from your `bots.conf` classification, so a guest's request fails the first
condition and never reaches the counter — bots and humans are not sharing a budget here.

Send **429 with `Retry-After`**, not 403: 429 is the status a well-behaved crawler acts on by
slowing down, and it does not signal "this page is gone". Remember that a big search engine
crawls from many addresses, so a per-IP limit slows one crawler thread rather than the crawl.

### 4.5 One budget shared across several paths

Two expensive endpoints, one combined allowance — a client cannot dodge the limit by
alternating between them:

```
search-flood  | uri~^/search  ; rate:30/10s bucket=expensive | reject:429 Too Many Requests
suggest-flood | uri~^/suggest ; rate:30/10s bucket=expensive | reject:429 Too Many Requests
```

| # | Request | Rule that matches | `expensive` count | Result |
| --- | --- | --- | --- | --- |
| 1–15 | `/search?q=…` | `search-flood` | 15 | 200 |
| 16–30 | `/suggest?q=…` | `suggest-flood` | 30 | 200 |
| 31 | `/search?q=…` | `search-flood` | 31 | **429** |
| 32 | `/suggest?q=…` | `suggest-flood` | 32 | **429** — the budget was already spent |

Without `bucket=`, each rule would get its own counter named after itself, and the client would
have 30 + 30.

**Every rule naming one bucket must declare the same limit and window.** They are one counter
array, sized once; a second rule asking for different numbers rebuilds it — the last one loaded
wins and the counts reset each time the rules load.

### 4.6 Enforce one limit while measuring a tighter one

You are running `100/10s` and suspect `50/10s` would be safe. Measure it against real traffic
without refusing anybody:

```
ip-flood-tighter | rate:50/10s  | reject:429 Too Many Requests | dry-run
ip-flood         | rate:100/10s | reject:429 Too Many Requests
```

Two counters, because `bucket` defaults to the rule name — the tighter rule measures, the live
rule enforces, and neither disturbs the other.

**Order matters here.** A dry-run rule logs and lets evaluation continue; a live rule that
matches stops it. With the dry-run rule *below* the live one, every request the live rule
refused would never reach it, and its measurement would be short exactly where it matters. Put
dry-run rules **above** the rules that act.

Read the two hit counts side by side (§6), and if the tighter rule's hits are all attackers,
swap the limits over.

### 4.7 Burst or sustained: choosing the window

`100/10s` and `20/2s` are the *same* sustained rate — 10 req/s — and behave completely
differently:

| What the client does in 10 s | `rate:100/10s` | `rate:20/2s` |
| --- | --- | --- |
| 10 req/s, evenly spread | all 100 pass | all pass — 20 per slot |
| 50 requests in the first 200 ms, then idle | all 50 pass | 20 pass, 30 refused |
| 300 requests as fast as it can | 100 pass, 200 refused | ~20 per 2 s slot pass, the rest refused |

A browser opening several tabs bursts; a scraper sustains. **If a client that bursts hard but
averages low should pass, keep the longer window. If it should not, shorten the window rather
than lowering the limit** — `20/2s` keeps the average and cuts the burst tolerance to a tenth.

**The boundary effect, stated plainly.** The window is a fixed slot, so a client can send
`limit` requests just before a boundary and `limit` more just after:

```
slot A                     | slot B
… 100 requests at t+9.9s   | 100 requests at t+10.1s   -> 200 requests in 200 ms, all allowed
```

That is inherent to counting per slot, and it is the price of the counter being two small
numbers per client. It still stops a client sending thousands per second, which is the job.

### 4.8 A node that only sees include traffic

By default `scope=toplevel`: a `<fc:include>` fragment re-entering the engine is **not**
counted. That is what you want almost always — a page stitched from twelve fragments costs its
visitor one request, not thirteen, so a page's budget does not depend on how it happens to be
built.

The exception is an inner node in a `browser → Frontcache edge → Frontcache filter` topology
(use case #3 in [deployment-usecases.md](deployment-usecases.md)), which may see fragment
traffic and nothing else:

```
inner-flood | rate:200/10s scope=all | reject:429 Too Many Requests
```

On that node, list the sibling edge in `front-cache.client-ip.trusted-proxies` — a Frontcache
node passes the original visitor's address on, and it is honoured only from a trusted peer, so
without it every fragment counts against the sibling's own address instead of the visitor's.

---

## 5. Try it on your laptop in five minutes

The [JSP example](../examples/frontcache-jsp) is a complete node with its own
`FRONTCACHE_HOME`, so nothing here touches a server. It needs **JDK 25**.

**1.** Add one rule to
[examples/frontcache-jsp/FRONTCACHE_HOME/conf/guard-rules.conf](../examples/frontcache-jsp/FRONTCACHE_HOME/conf/guard-rules.conf):

```
demo-flood | uri~^/example/index\.jsp$ ; rate:3/10s | reject:429 Too Many Requests
```

Scope it to one URI on purpose: everything from your laptop is the same address, so an
unscoped rule would refuse your own browser, your curl, and the reload call you are about to
make.

No `trusted-proxies` needed here — you connect straight to the embedded Jetty, so the socket
peer *is* the client. That is the one deployment where leaving it empty is right.

**2.** Start it:

```bash
cd examples/frontcache-jsp && ./gradlew appRun
```

**3.** Spend the budget:

```bash
for i in 1 2 3 4 5; do curl -s -o /dev/null -w "%{http_code} " http://localhost:8080/example/index.jsp; done; echo
```

```
200 200 200 429 429
```

If you see a fourth `200`, your burst straddled a slot boundary and the count reset
mid-loop (§4.7) — run it again.

**4.** Look at the refusal:

```bash
curl -sD - -o /dev/null http://localhost:8080/example/index.jsp | grep -iE "^HTTP/|retry-after|cache-control"
```

```
HTTP/1.1 429 Too Many Requests
Cache-Control: no-store, no-cache, max-age=0, must-revalidate
Retry-After: 6
```

`Retry-After` is whatever is left of the slot, so it counts down as you repeat the command.
`no-store` is on every guard response: a cached 429 would pin a client to a rejection long
after its window ended.

**5.** Wait for the slot to roll over:

```bash
sleep 10 && curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/example/index.jsp
```

```
200
```

**6.** Notice what did *not* happen. `index.jsp` stitches in `header.jsp` and `footer.jsp` with
`<fc:include>`, so each of those three page views was **three** passes through the engine — and
the visitor was charged one each time. That is `scope=toplevel` doing its job.

**7.** Two things this example does not have, so you do not go looking for them:

- **No management endpoint.** Its filter is mapped to `/example/*` only, so there is no
  `/frontcache-io` to call — pick up an edited rule by restarting (`Ctrl-C`, `./gradlew
  appRun`). On a standalone node, that same edit goes live with `reload-guard-rules` and no
  restart (§3, step 4), which is the path you will use in production.
- **No failed-requests log.** The bundled `fc-logback.xml` wires the request and fallback logs
  but not `frontcache.failed-requests`, which is where guard actions are written — so here the
  status codes above are the evidence. The standalone server's shipped config has that logger,
  which is the log §6 describes.

The [Spring Boot example](../examples/frontcache-spring) behaves the same way, with its own
`FRONTCACHE_HOME/conf/guard-rules.conf`.

---

## 6. What you see when it fires

**The client** gets `429`, a plain-text body of whatever you wrote after the status
(`Too Many Requests`), `Retry-After`, and `Cache-Control: no-store`.

**The log** — one line per action in `FRONTCACHE_HOME/logs/frontcache-failed-requests.log`:

```
2026-09-20T11:02:41,118-0600 9f3c1a20 www.example.com GET error toplevel direct rejected 0 -1 "www.example.com/search?q=boots" "203.0.113.7" fc-us-1 guest "python-requests/2.32" "search-flood#203.0.113.7" 429
```

The reason field is `<rule>#<address>` — **the address the limiter actually counted**. It is
recorded because the limiter's resolution (trusted proxies only) and the log's own client-IP
column can legitimately differ, and "which address was counted?" is the first question anyone
debugging a 429 asks. A dry-run match writes the same line shape with the reason
`search-flood:dry-run`, disposition `dry-run` and status `0`; there the address to read is the
log's client-IP column.

**The console** — *Configs → Guard Rules* (`http://<console>:7080/guard-rules`) lists the rules
in evaluation order with the condition as written and a **Hits** column. For a rate rule, hits
are requests refused (or, for a dry-run rule, requests that would have been).

**Metrics**, with `front-cache.metrics.export` on
([console-dashboards.md](console-dashboards.md)):

```
frontcache_guard_actions_total{name="ip-flood", result="rejected"}
frontcache_guard_actions_total{name="ip-flood-tighter", result="dry-run"}
frontcache_guard_ratelimit_takeovers_total{name="ip-flood"}
```

`result` uses the log's own vocabulary — `rejected` / `redirected` / `dry-run` / `allowed` — so
a Grafana panel and a Kibana panel name the same event with the same word. Sizing a limit
before it acts is then a query:

```promql
sum(rate(frontcache_guard_actions_total{result="dry-run"}[5m])) by (name)
```

**No series is labelled by client address** — that is unbounded cardinality. Per-address detail
lives in the log, where retention bounds it.

**Kibana** — a rate rejection is a `rejected` disposition like any other, so it lands in the
**Frontcache Rejected Requests** dashboard of the
[log-analytics example](../examples/log-analytics) with no pipeline change. One thing to adjust
there: a panel aggregating on `reason` will split one rate rule into one term per client
address, so split `reason` on `#` and keep the address as its own field.

---

## 7. Sizing the limit

1. **Measure, don't guess.** Run the rule in dry-run for a day and look at the hit count and
   the addresses behind it. The question is never "is 100 a good number", it is "how many real
   visitors would 100 have refused".
2. **Count requests through the node, not page views.** Includes do not count, but if assets
   are proxied through Frontcache, every one of them does.
3. **Leave headroom for bursts.** A browser opening tabs, a prefetching client, and a
   single-page app warming its data all arrive in a clump. Pick the window with §4.7 in mind.
4. **The limit is per node, not per fleet.** Each node counts what it sees, in memory. Behind
   *N* edges, `100/10s` is a fleet limit of roughly `100 × N` for a client whose requests
   spread — so configure `limit ≈ fleet_target / N`, **and re-check it when you resize the
   fleet**: halving the fleet doubles each client's effective allowance. There is no shared
   counter, deliberately — a network hop on the request path of a component whose purpose is
   surviving the origin being down is a bad trade.
5. **Watch the takeover counter.** Counters live in a fixed array per bucket
   (`front-cache.guard-rules.rate-limit.slots`, 262144 slots ≈ 2 MB by default), not a map
   keyed by client address — the key space is attacker-chosen, so a map would be a
   memory-exhaustion primitive. Two addresses landing in one slot make the arriving one take it
   over and **start a fresh count**, so undersizing costs accuracy, never a wrongly refused
   visitor. `frontcache_guard_ratelimit_takeovers_total` climbing with traffic is the signal to
   raise `slots`.
6. **Counters survive a reload.** Editing an unrelated rule during an attack does not hand
   every attacker a fresh window. They reset on restart, and when a bucket's own limit or
   window changes.

---

## 8. When something does not work

| Symptom | Cause | Fix |
| --- | --- | --- |
| Everyone is refused at once; log reason reads `ip-flood#127.0.0.1` | `front-cache.client-ip.trusted-proxies` is empty while something proxies to Frontcache, so the whole site keys on the proxy | Set it and restart (§3, step 1). Frontcache also `WARN`s about this at startup and on reload |
| The rule never fires | An `allow` rule above it matched first, the condition never matched (regexes are unanchored — `uri~/admin` also matches `/x/admin`), or it is still `dry-run` | Check the Hits column in the console; a rule with hits but no effect is dry-run or shadowed |
| The rule vanished after a reload | One bad line is skipped, the rest of the file keeps working | `logs/error.log`: `Skipping guard rule (guard-rules.conf:NN): …`. The parser refuses a non-positive limit, an unparseable window, a window over 1h, an unknown attribute, a negated `!rate:`, and two `rate:` predicates in one rule |
| Real visitors are being refused | The limit is too low for the burst your traffic actually has | Re-measure with a dry-run twin (§4.6), or keep the rate and lengthen the window (§4.7) |
| Hit counts reset unexpectedly | A restart, or a change to that bucket's limit or window | Expected. Prometheus reads it as the counter reset it is |
| Two rules share a bucket and the counts keep resetting | They declare different limits or windows | Make them identical (§4.5) |
| `frontcache_guard_ratelimit_takeovers_total` keeps climbing | The slot array is too small for the address space | Raise `front-cache.guard-rules.rate-limit.slots` (restart) |
| Need it off **now** | — | Comment the rule out and `action=reload-guard-rules` — instant. `front-cache.guard-rules.rate-limit.enabled=false` disables every `rate:` predicate while leaving other guard rules working, but it is a property, so it applies at the next restart |

Settings reference — all the properties, with defaults, are in
[guard-getting-started.md §9](guard-getting-started.md). The short version: **rules reload,
properties need a restart**.

---

## 9. What this does not do

Read this before you rely on it. The gap between "we have rate limiting" and what a per-IP
limiter actually buys is where people get hurt.

- **Distributed attacks.** Ten thousand hosts under a `100/10s` limit are permitted 100 000
  requests per 10 s. This stops one loud client, not a botnet.
- **Address rotation, and IPv6 especially.** One address is one counter, with no subnet
  grouping — so a shared-NAT office is never limited because of one user behind it, and a
  client holding an IPv6 `/64` (a normal residential or cloud allocation) can send every
  request from a different address and never be counted twice. Rotating IPv4 proxy pools do the
  same. This is the largest known gap, and it is a deliberate trade.
- **Slow, expensive requests.** One query per second that each cost the origin two seconds is
  invisible to a request *counter*. That is a concurrency limit, and it is what the circuit
  breakers in [resilience-command-flow.md](resilience-command-flow.md) are for.
- **Bursts across a window boundary.** Up to `2 × limit` in a short span, by construction
  (§4.7).
- **Anything content-based** — injection, payload inspection, credential validity. Not a WAF.
- **Layer 3/4 floods.** They never reach a servlet.

Every failure path fails **open**: an address that cannot be resolved is not metered, counter
contention under-counts rather than refuses, and a predicate that throws is logged and treated
as no-match. A rate limiter that fails closed converts its own bug into the outage it exists to
prevent.

---

Guard rules in general: [guard-getting-started.md](guard-getting-started.md) ·
How a request flows: [concept.md](concept.md) ·
Topologies: [deployment-usecases.md](deployment-usecases.md) ·
Metrics and dashboards: [console-dashboards.md](console-dashboards.md) ·
Logs into Kibana: [examples/log-analytics](../examples/log-analytics)
