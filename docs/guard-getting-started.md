# Guard rules — getting started

Guard rules let Frontcache answer a request **before it touches the cache or your
origin**. A rule says *when* (a condition) and *what* (reject, redirect, or let
through). Requests a rule handles cost you nothing downstream: no cache lookup, no
origin connection, no include stitching.

A condition can be about what the request *is* — its URI, host, method, cookies,
headers — or, since 2.8.0, about **how often it arrives**: `rate:100/10s` is true once
one client IP has sent a hundred requests inside a ten-second slot (§5).

Two rules ship built in and are already protecting you:

| Rule | What it does |
| --- | --- |
| `uri-too-long` | 414 for a URL longer than `front-cache.max-request-uri-length` (4096 by default) |
| `bad-request` | 400 for structurally invalid query params (the `&amp;amp;`-mangled kind a broken crawler produces) |

Everything else you add yourself, in one file.

---

## 1. Where the rules live

```
FRONTCACHE_HOME/conf/guard-rules.conf
```

The file ships with every rule commented out, so nothing changes until you decide it
should. Rules are **global** — one file per node, no per-domain variant. A rule that
should only apply to one site says so in its condition (`host~^www\.example\.com$`).

Apply an edit without restarting:

```bash
curl -H "Authorization: Bearer <your-api-key>" "http://<edge>/frontcache-io?action=reload-guard-rules"
```

## 2. The format

```
<name> | <condition> | <action> [| dry-run]
```

- **name** — a short slug you choose. It is what you will see in the console, in the
  logs, and as the metric in Kibana. Pick something you will recognise at 3am.
- **condition** — one or more checks separated by `;`. **All** must hold. Put `!` in
  front of a check to negate it.
- **action** — `allow`, `reject:…`, or `redirect:…`.
- **dry-run** — optional. The rule is evaluated and logged, but the request is left
  alone. Always start here (§6).

Rules run **top to bottom, first match wins**, after the two built-ins. Blank lines
and `#` comments are ignored.

### Conditions you can use

| Condition | True when |
| --- | --- |
| `host:ip` | the request came to an IP address instead of a hostname |
| `host~<regex>` | the hostname matches |
| `uri~<regex>` | the path matches (no query string) |
| `query~<regex>` | the query string matches (`?` included) |
| `url~<regex>` | the whole URL matches |
| `method:GET` | the HTTP method is GET (POST, HEAD, …) |
| `client-type:bot` \| `client-type:browser` | how Frontcache classified the visitor, using `bots.conf` |
| `cookie:<name>` | that cookie is present (presence only — values are never read) |
| `header:<name>` or `header:<name>~<regex>` | the header is present / matches |
| `request-type:<toplevel\|include\|include-async>` | `toplevel` is a request from a client; the others are `<fc:include>` fragments re-entering the engine |
| `rate:<limit>/<window>` | one client IP has already sent `<limit>` requests in the current `<window>` — **§5**, and it needs one property set first |

Regexes are Java regexes, matched anywhere in the value (`find()` semantics) — the
same behaviour as `dynamic-urls.conf`. Anchor with `^` when you mean "starts with".

### Actions you can take

| Action | Effect |
| --- | --- |
| `allow` | stop checking, let the request through — this is how you write exemptions |
| `reject:<status> [message]` | send the status and a short plain-text body (default body: `"<status> Rejected"`) |
| `redirect:<301\|302\|303\|307\|308> <target>` | send a `Location` header, plus `Cache-Control: no-store` so nobody caches the decision |

A redirect target can be relative (`/login.htm`) or absolute. Absolute targets must
point at a host you serve — see §8 if a rule is refused.

Targets can carry the current request:

| Placeholder | Becomes |
| --- | --- |
| `${uri}` | `/en/ccc/va/analytics.htm` |
| `${query}` | `?itemTypeFQ=itemType:coin` |
| `${url}` | the full URL |
| `${url:enc}` | the full URL, URL-encoded — for `?return=…` |
| `${host}` | the request hostname |

---

## 3. Recipe: send scanners away from your IP

Bots that walk your server by IP address (`http://160.202.254.65/…`) are never real
visitors, but every request they make is a cache miss and an origin render.

```
# exemptions FIRST - these paths must keep working when addressed by IP
ping-by-ip     | uri~^/fc-ping\.jsp$        | allow
mgmt-by-ip     | uri~^/frontcache-io        | allow
dash-stream    | uri~^/fc-dashboard\.stream | allow
legacy-stream  | uri~^/hystrix\.stream      | allow

ip-access      | host:ip                    | redirect:301 https://www.example.com/en/welcome.htm
```

**The exemptions are not optional.** Your load balancer health-checks
`/fc-ping.jsp` by IP, and `frontcache-agent`, the console, and cache replication call
`/frontcache-io` and the dashboard stream the same way. The stream answers on both
`/fc-dashboard.stream` and the legacy `/hystrix.stream`, so exempt both — external
Turbine and older consoles still use the second one. Without the exemptions above the
rule redirects your own infrastructure and the node looks unhealthy. Frontcache logs a
warning at startup if it spots this, but the file is where you fix it.

**An absolute redirect target needs its host allowed**, or the rule is refused at load
(open-redirect protection — nothing is allowed implicitly, including your own site):

```properties
front-cache.guard-rules.allowed-redirect-hosts=www.example.com
```

## 4. Recipe: send logged-out visitors to the login page

Account-only pages render at origin only to conclude "you need to log in". If your
app sets a session cookie (here `hruc`), the edge can make that decision:

```
login-page     | uri~^/login\.htm                          | allow
anon-analytics | uri~^/[a-z]{2}/ccc/va/ ; !cookie:hruc     | redirect:302 https://www.example.com/login.htm?return=${url:enc}
```

Worth knowing:

- **The `login-page` exemption prevents a redirect loop.** If the login page itself
  could match the rule's pattern, every visit would bounce forever.
- **`www.example.com` must be in `front-cache.guard-rules.allowed-redirect-hosts`**, as in §3.
- **302, not 301.** The decision depends on a cookie that changes the moment someone
  logs in; a browser that cached a 301 would strand them.
- **Bots get redirected too.** They carry no session cookie, so crawlers stop costing
  you origin renders on login-gated pages — but `/login.htm` absorbs that crawl
  traffic, and those URLs will leave search results. If you would rather crawlers
  render normally, add `; client-type:browser` to the condition.
- **Presence only.** Frontcache checks that the cookie *exists*; it never validates a
  session. Deciding whether a session is real stays your app's job.

---

## 5. Rate limiting: how often, not what

New in **2.8.0**. One predicate, usable in any rule, alongside every condition above:

```
rate:<limit>/<window> [bucket=<name>] [scope=<toplevel|all>]
```

It is true once **one client IP** has sent `<limit>` requests inside the current
`<window>`, and for every further request until the window rolls over.

| Field | Meaning | Default |
| --- | --- | --- |
| `<limit>` | requests one IP may send inside a window, integer > 0 | — |
| `<window>` | `<n>s`, `<n>m` or `<n>h`; a bare unit means 1, so `/s` is `/1s`. Max 1h | — |
| `bucket=<name>` | counter namespace; rules sharing a name share one counter | the rule name |
| `scope=` | `toplevel` counts client requests only; `all` also counts includes | `toplevel` |

Both numbers live in the rule, so `reload-guard-rules` changes them without a restart.

```
# exemptions FIRST, as always - monitoring polls fast, and from one address
ping-by-ip      | uri~^/fc-ping\.jsp$            | allow
mgmt-by-ip      | uri~^/frontcache-io            | allow
fc-metrics      | uri~^/fc-metrics$              | allow
dash-stream     | uri~^/fc-dashboard\.stream     | allow

# 1. blanket per-IP brake: 100 requests per 10-second slot. Ship it dry-run and watch.
ip-flood        | rate:100/10s                   | reject:429 Too Many Requests | dry-run

# 2. tighter, on the expensive uncacheable path only
search-flood    | uri~^/search\.htm ; rate:20/10s | reject:429 Too Many Requests

# 3. credential stuffing: only POSTs to that one page feed the counter
login-flood     | uri~^/login\.htm$ ; method:POST ; rate:10/1m | reject:429 Slow down

# 4. a misbehaving crawler gets slowed, not blocked; browsers never touch the counter
bot-flood       | client-type:bot ; rate:100/10s | reject:429 Crawl slower
```

Nothing else about guard rules changes. `allow` rules above are still how you exempt a
path, `| dry-run` (§6) is still how you ship one safely, the rejection still writes one
line to `frontcache-failed-requests.log` with the rule name as the reason, and the
console renders a rate rule and its hit count like any other.

### 5.1 The one thing you must configure

**`front-cache.client-ip.trusted-proxies`.** Read this before you enable a rate rule.

Frontcache never reads `X-Forwarded-For` — or any other forwarding header — from a peer
you have not declared trusted. It cannot: those headers are client-supplied, so a client
sending its own would mint itself a fresh counter on every request and defeat the limiter
completely, while passing any test that did not try it.

The consequence: with this key **empty**, the limiter keys on the socket peer — and if
something terminates TLS in front of Frontcache and proxies to `127.0.0.1:9080`, that is
`127.0.0.1` for every visitor on earth. **Your whole site would count as one client.**
Frontcache `WARN`s at startup and on every reload when a `rate:` rule is loaded and this
key is empty; it does not refuse to start, because a guard misconfiguration should degrade
to "Frontcache as usual" rather than to an outage.

```properties
# whatever actually terminates the client connection: the local nginx, your CDN's egress
# ranges, sibling Frontcache nodes
front-cache.client-ip.trusted-proxies=127.0.0.1/32,::1/128,10.0.0.0/8

# read only from a trusted peer, and walked RIGHT TO LEFT - the leftmost entry is whatever
# the client wrote; the rightmost non-trusted one is what the last proxy we trust observed
#front-cache.client-ip.header=X-Forwarded-For
```

The client-IP column of the request logs is resolved separately and is **unchanged**, so
your ELK pipeline and saved Kibana searches are untouched. The two resolutions can
legitimately disagree, which is why a rate-limit rejection logs its reason as
`<rule>#<address>` (§7): *which address was actually counted* is the first question anyone
debugging a 429 asks.

### 5.2 What the numbers mean

- **The window is a fixed slot, not a rolling period.** `100/10s` permits 100 requests in a
  slot and resets at the boundary — so up to 200 across one boundary, by construction. It
  still stops a client sending thousands per second, which is the job. If burst tolerance
  matters more than the average, shorten the window rather than lowering the limit: `20/2s`
  is the same sustained rate with a tenth of the burst.
- **The limit is per node, not per fleet.** Each node counts what it sees, in memory.
  Behind *N* edges, `100/10s` is a fleet limit of roughly `100 × N` for a client whose
  requests spread — so configure `limit ≈ fleet_target / N`, and re-check it when you
  resize the fleet. There is no shared counter, deliberately: a network hop on the request
  path of a component whose purpose is surviving the origin being down is a bad trade.
- **One address is one counter.** No prefix or subnet grouping, so a shared-NAT office is
  never limited because of one user behind it. The cost, plainly: an IPv6 client holding a
  `/64` can rotate addresses and evade the limit, and so can a rotating IPv4 pool.
- **Includes do not spend a visitor's budget.** A page stitched from twelve `<fc:include>`
  fragments costs its visitor one request, not thirteen — that is what the default
  `scope=toplevel` is for. Use `scope=all` only on a node fronted by another Frontcache,
  which sees include traffic and nothing else.
- **Counts survive `reload-guard-rules`.** Editing an unrelated rule during an attack does
  not hand every attacker a fresh window. They reset on restart, and when a rule's limit or
  window changes.
- **`bucket` defaults to the rule name**, so a dry-run copy of a rule measures a stricter
  limit against real traffic without disturbing the live one:
  `ip-flood-tighter | rate:50/10s | reject:429 | dry-run` beside
  `ip-flood | rate:100/10s | reject:429`.

### 5.3 The 429

A rate rejection carries **`Retry-After`** — the seconds until that client's window rolls
over, which RFC 6585 expects and a well-behaved crawler acts on. All guard rejections, rate
rules or not, also carry `Cache-Control: no-store`: a cached 429 would pin a client to a
rejection long after its window ended.

### 5.4 Two rules the parser enforces

- **`rate:` is always evaluated last within its rule**, whatever order you wrote the
  conditions in. Written as `rate:5/10s ; uri~^/search`, a naive left-to-right evaluation
  would count the whole site's traffic and then report hits against a URI it never matched.
  Frontcache moves it to the end so the counter only ever sees the requests the rest of the
  condition selected.
- **It cannot be negated, and there can be only one per rule.** `!rate:` would count on the
  allowed path and read as admission control; two rate predicates AND-ed would consume both
  budgets on every request, which is not what anyone means by it. Both are refused at load
  with an explanatory error — write the rule around the over-limit case, and use `allow`
  rules above it for exemptions.

### 5.5 What this is not

Not a WAF, not bot management, and not a defence against a distributed botnet. Under
`100/10s`, ten thousand hosts are still permitted a hundred thousand requests per ten
seconds. It stops **one loud client** — the scraper walking your catalogue, the credential
stuffing loop, the client re-requesting an uncacheable search URL until your origin thread
pool fills. That is the traffic it exists for.

Size it with `dry-run` first (§6), always.

---

## 6. Roll a rule out safely

Append `| dry-run` and the rule watches without acting:

```
anon-analytics | uri~^/[a-z]{2}/ccc/va/ ; !cookie:hruc | redirect:302 https://www.example.com/login.htm | dry-run
```

1. Add the rule with `dry-run`, reload.
2. Watch what it *would* have caught — the console shows a hit count, and the Kibana
   dashboard has a **"Dry-run rules — what they WOULD have caught"** table with the
   client IPs and URLs behind it (§7).
3. Happy? Drop `| dry-run`, reload again.
4. Not happy? Delete the line, reload. Nothing was ever sent to a visitor.

To switch everything configured off at once — leaving the two built-ins active —
comment out the rules (or empty the file) and reload. That takes effect immediately.
The `front-cache.guard-rules.enabled=false` property does the same thing permanently,
but properties are read at startup, so it needs a restart.

## 7. See what your rules are doing

**Console** — *Configs → Guard Rules* (`http://<console>:7080/guard-rules`). One tab
per edge, rules in evaluation order, with a **Hits** column and an `active` / `dry-run`
badge. A rule with no hits is doing nothing; a rule you did not expect at the top
explains why a later one never fires.

**Command line** — same data, per node:

```bash
curl -H "Authorization: Bearer <your-api-key>" "http://<edge>/frontcache-io?action=get-guard-rules"
```

**Logs** — every guard action writes one line to
`FRONTCACHE_HOME/logs/frontcache-failed-requests.log`, with the rule name and the
status sent:

```
2026-08-18T10:49:14,638-0600 5606c79f … direct redirected 0 -1 "127.0.0.1/whatever.htm" "160.202.254.65" fc-us-1 browser "curl/8.7.1" "ip-access" 301
```

For a **rate rule** the reason field carries the address that was counted, as
`<rule>#<address>` — `"ip-flood#203.0.113.7"` — because the limiter's resolved address
(§5.1) and the log's own client-IP column can legitimately differ, and telling them apart
is the whole of debugging a 429. A Kibana pipeline aggregating on `reason` should split it
on `#` so the by-rule panels keep counting rules rather than one term per client.

**Metrics** — with `front-cache.metrics.export` on
([console-dashboards.md](console-dashboards.md)), every guard rule is a series, so "is this
rule firing?" and "would my new dry-run rule refuse real visitors?" can be alerted on
instead of grepped for:

```
frontcache_guard_actions_total{name="ip-flood", result="rejected"}
frontcache_guard_actions_total{name="ip-flood", result="dry-run"}
frontcache_guard_ratelimit_takeovers_total{name="ip-flood"}
```

`result` uses the log's own vocabulary — `rejected` / `redirected` / `dry-run`, plus
`allowed` for exemption rules — so a Grafana panel and a Kibana panel name the same event
with the same word. Sizing a limit before it acts is then a query:

```promql
sum(rate(frontcache_guard_actions_total{result="dry-run"}[5m])) by (name)
```

**No series is labelled by client address** — that is unbounded cardinality, and the address
belongs in the log, where retention bounds it. The one sizing signal to watch is
`frontcache_guard_ratelimit_takeovers_total` rising with traffic: it means
`front-cache.guard-rules.rate-limit.slots` is too small for the address space being seen.
Counters reset when `reload-guard-rules` rebuilds the rules, which Prometheus reads as the
counter reset it is.

**Kibana** — pull the logs into the [log-analytics example](../examples/log-analytics)
and open the **Frontcache Rejected Requests** dashboard:

```bash
cd examples/log-analytics
./start-fc-elk.sh
./pull-logs.sh "fc-us fc-eu"
# http://localhost:5601/app/dashboards#/view/fc-rejected
```

It breaks everything down by rule, HTTP status, node, domain, country, client IP, URL
and user agent, and separates *rejected* / *redirected* / *dry-run* / circuit-breaker
fallbacks; that example's
[README](../examples/log-analytics/README.md) covers the setup and every parsed field.

Hit counts are in memory: they reset when the node restarts or when you reload the
rules. The logs and dashboard are the durable record.

---

## 8. When something does not work

**"My rule does nothing."** Check the console's Hits column. Zero hits means the
condition never matched — remember regexes are unanchored, so `uri~/admin` matches
`/x/admin` too, and `uri~^/admin` is usually what you want. Hits but no visible
effect means an earlier `allow` rule matched first, or the rule is still `dry-run`.

**"The rule vanished after a reload."** One bad line is skipped, not the whole file.
Look in `logs/error.log` for:

```
Skipping guard rule (guard-rules.conf:20): <what was wrong>
```

Common causes: an invalid regex, an unknown condition name, a missing action, or a
redirect status that is not a redirect.

**"Redirect target host is not allowed."** Open-redirect protection. Absolute targets
must point at a host listed in `front-cache.guard-rules.allowed-redirect-hosts` — and that
is the *only* source of allowed hosts, so **your own site is not implicitly allowed**. Add
the host, or use a relative target.

**"…redirects to a URL its own condition matches."** A redirect loop, caught at load
time. Add an `allow` exemption for the destination above the rule (as in §4).

**"Health checks started failing."** An `ip-access`-style rule without the exemptions
from §3. Frontcache warns about this at startup:

```
Guard rule 'ip-access' would redirect:301 … the GSLB health check (/fc-ping.jsp) - add an 'allow' rule for it ABOVE that rule
```

**"My rate rule refuses everybody at once."** Or: the whole site counts as one
client. `front-cache.client-ip.trusted-proxies` is empty while something proxies to
Frontcache, so every request keys on the proxy's address — §5.1. Frontcache warns about
this at startup whenever a `rate:` rule is loaded and the key is empty. Set it, restart,
and check a rejection's log reason: `ip-flood#127.0.0.1` is the symptom, a real client
address is the fix landing.

**"My rate rule was skipped at load."** `logs/error.log` names the reason. The parser
refuses a non-positive limit, an unparseable window, a window over an hour, an unknown
attribute, a negated `!rate:`, and two rate predicates in one rule (§5.4). As always the
line is skipped and the rest of the file keeps working.

**"Everything looks broken and I need it off now."** Empty `guard-rules.conf` (or
comment every line) and reload:

```bash
curl -H "Authorization: Bearer <your-api-key>" "http://<edge>/frontcache-io?action=reload-guard-rules"
```

Every configured rule stops instantly; the two built-ins stay. Use
`front-cache.guard-rules.enabled=false` for a permanent switch-off — it is read at
startup, so it applies at the next restart, not on reload. To disable **only** rate
limiting while keeping every other rule, `front-cache.guard-rules.rate-limit.enabled=false`
makes every `rate:` predicate false — the rules stay visible in the console.

A rule that throws an unexpected error is logged and ignored for that request —
Frontcache keeps serving as if the rule were not there.

---

## 9. Settings reference

In `FRONTCACHE_HOME/conf/frontcache.properties`. **These are read at startup** — a
change needs a node restart, unlike `guard-rules.conf`, which `reload-guard-rules`
re-reads live:

| Property | Default | Meaning |
| --- | --- | --- |
| `front-cache.guard-rules.enabled` | `true` | load `guard-rules.conf` at all |
| `front-cache.guard-rules.allowed-redirect-hosts` | *(empty)* | every host an absolute redirect may target (comma-separated). Nothing is allowed implicitly — not even `front-cache.default-domain` |
| `front-cache.guard-rules.bad-request.enabled` | `true` | the built-in 400 rule |
| `front-cache.max-request-uri-length` | `4096` | the built-in 414 rule; `0` or less disables it |
| `front-cache.client-ip.trusted-proxies` | *(empty)* | CIDRs whose forwarding headers are believed. **Required behind a proxy or CDN before any `rate:` rule means anything** — §5.1 |
| `front-cache.client-ip.header` | `X-Forwarded-For` | which header to read, when the peer is trusted |
| `front-cache.guard-rules.rate-limit.enabled` | `true` | emergency off switch for rate limiting only; every `rate:` predicate returns false and the rules stay visible |
| `front-cache.guard-rules.rate-limit.slots` | `262144` | counter slots per bucket, 8 bytes each — 2 MB. Undersizing costs accuracy, never a wrongly refused visitor |
| `front-cache.guard-rules.rate-limit.max-total-slots` | `4194304` | ceiling across all buckets; a rule that would exceed it is skipped with an error |

Management actions (both need the `Authorization: Bearer <api-key>` header when an api key is
configured):

| Action | Purpose |
| --- | --- |
| `get-guard-rules` | list the rules this node is running, in order, with hit counts |
| `reload-guard-rules` | re-read `guard-rules.conf` without a restart (resets hit counts) |

Counters are a fixed array sized at startup, not a map keyed by client address: the key
space is attacker-chosen, so a map would be a memory-exhaustion primitive.

Guard rules work the same in both deployment modes — standalone reverse proxy and
servlet-filter — because both enter Frontcache through the same request path.
