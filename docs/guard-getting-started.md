# Guard rules — getting started

Guard rules let Frontcache answer a request **before it touches the cache or your
origin**. A rule says *when* (a condition) and *what* (reject, redirect, or let
through). Requests a rule handles cost you nothing downstream: no cache lookup, no
origin connection, no include stitching.

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
curl -H "x-frontcache-site-key: <your-site-key>" "http://<edge>/frontcache-io?action=reload-guard-rules"
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
  alone. Always start here (§5).

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

Regexes are Java regexes, matched anywhere in the value (`find()` semantics) — the
same behaviour as `dynamic-urls.conf`. Anchor with `^` when you mean "starts with".

### Actions you can take

| Action | Effect |
| --- | --- |
| `allow` | stop checking, let the request through — this is how you write exemptions |
| `reject:<status> [message]` | send the status and a short plain-text body (default body: `"<status> Rejected"`) |
| `redirect:<301\|302\|303\|307\|308> <target>` | send a `Location` header, plus `Cache-Control: no-store` so nobody caches the decision |

A redirect target can be relative (`/login.htm`) or absolute. Absolute targets must
point at a host you serve — see §7 if a rule is refused.

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

## 5. Roll a rule out safely

Append `| dry-run` and the rule watches without acting:

```
anon-analytics | uri~^/[a-z]{2}/ccc/va/ ; !cookie:hruc | redirect:302 https://www.example.com/login.htm | dry-run
```

1. Add the rule with `dry-run`, reload.
2. Watch what it *would* have caught — the console shows a hit count, and the Kibana
   dashboard has a **"Dry-run rules — what they WOULD have caught"** table with the
   client IPs and URLs behind it (§6).
3. Happy? Drop `| dry-run`, reload again.
4. Not happy? Delete the line, reload. Nothing was ever sent to a visitor.

To switch everything configured off at once — leaving the two built-ins active —
comment out the rules (or empty the file) and reload. That takes effect immediately.
The `front-cache.guard-rules.enabled=false` property does the same thing permanently,
but properties are read at startup, so it needs a restart.

## 6. See what your rules are doing

**Console** — *Configs → Guard Rules* (`http://<console>:7080/guard-rules`). One tab
per edge, rules in evaluation order, with a **Hits** column and an `active` / `dry-run`
badge. A rule with no hits is doing nothing; a rule you did not expect at the top
explains why a later one never fires.

**Command line** — same data, per node:

```bash
curl -H "x-frontcache-site-key: <your-site-key>" "http://<edge>/frontcache-io?action=get-guard-rules"
```

**Logs** — every guard action writes one line to
`FRONTCACHE_HOME/logs/frontcache-failed-requests.log`, with the rule name and the
status sent:

```
2026-08-18T10:49:14,638-0600 5606c79f … direct redirected 0 -1 "127.0.0.1/whatever.htm" "160.202.254.65" fc-us-1 browser "curl/8.7.1" "ip-access" 301
```

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

## 7. When something does not work

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

**"Everything looks broken and I need it off now."** Empty `guard-rules.conf` (or
comment every line) and reload:

```bash
curl -H "x-frontcache-site-key: <your-site-key>" "http://<edge>/frontcache-io?action=reload-guard-rules"
```

Every configured rule stops instantly; the two built-ins stay. Use
`front-cache.guard-rules.enabled=false` for a permanent switch-off — it is read at
startup, so it applies at the next restart, not on reload.

A rule that throws an unexpected error is logged and ignored for that request —
Frontcache keeps serving as if the rule were not there.

---

## 8. Settings reference

In `FRONTCACHE_HOME/conf/frontcache.properties`. **These are read at startup** — a
change needs a node restart, unlike `guard-rules.conf`, which `reload-guard-rules`
re-reads live:

| Property | Default | Meaning |
| --- | --- | --- |
| `front-cache.guard-rules.enabled` | `true` | load `guard-rules.conf` at all |
| `front-cache.guard-rules.allowed-redirect-hosts` | *(empty)* | every host an absolute redirect may target (comma-separated). Nothing is allowed implicitly — not even `front-cache.default-domain` |
| `front-cache.guard-rules.bad-request.enabled` | `true` | the built-in 400 rule |
| `front-cache.max-request-uri-length` | `4096` | the built-in 414 rule; `0` or less disables it |

Management actions (both need the `x-frontcache-site-key` header when a site key is
configured):

| Action | Purpose |
| --- | --- |
| `get-guard-rules` | list the rules this node is running, in order, with hit counts |
| `reload-guard-rules` | re-read `guard-rules.conf` without a restart (resets hit counts) |

Guard rules work the same in both deployment modes — standalone reverse proxy and
servlet-filter — because both enter Frontcache through the same request path.
