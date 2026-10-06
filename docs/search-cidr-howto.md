# Finding the network behind the traffic — a `search-cidr.sh` how-to

A walk from "something is hammering us" to the **address blocks** it is coming from, using
[`examples/log-analytics/search-cidr.sh`](../examples/log-analytics/search-cidr.sh) against a
request log — and from those blocks to the line in `bots.conf` or `guard-rules.conf` that acts
on them.

The script is a single `awk` pass over a log file. It needs no stack, no containers and no
Elasticsearch: it is the five-second answer for the question the
[Kibana dashboards](../examples/log-analytics/README.md) answer with more ceremony, and it runs
on a log you just `scp`'d. The `client-ip:` predicate it feeds arrived in **2.9.0**.

---

## 1. Why you would want this

The per-IP view of a log is usually a disappointment. You sort by address, and the top of the
list is a dozen hosts with a few dozen requests each — nothing that looks like a culprit, no
single address worth naming in a rule. The traffic is still there; it is just spread out.

It is spread out because **a client that costs you money rarely arrives from one address**:

- **Cloud ranges.** A scraper rented in a hyperscaler gets a new address per instance, and
  sometimes per request. Each one looks small. The account behind them is one actor, and the
  provider publishes the range.
- **Residential and mobile pools.** Carrier-grade NAT and proxy resellers rotate addresses
  constantly. Here even the **user** is not stable — but the pool is.
- **Cookie-less crawler fleets.** A well-behaved crawler announces itself in the User-Agent and
  you classify it in `bots.conf`. An unannounced one is spread across a fleet, and the only
  thing its requests share is the network they come from.

Aggregating by prefix puts the shared thing back in one row. The rented fleet that was 28
separate `/24` rows becomes two `/16`s (§4.3), and then it is one line of configuration.

**Why the prefix is also the unit you can act on.** As of 2.9.0 the `client-ip:` predicate
takes a CIDR, in both `guard-rules.conf` and `bots.conf`, so the block this script prints is
already the shape a rule wants (§5). An individual address is not: by the time you have
deployed a rule naming it, the pool has moved on.

---

## 2. The command, in thirty seconds

```bash
cd examples/log-analytics
./search-cidr.sh -n 20 -m 24 "toplevel.*no-js-proof"
```

Read the rows matching a regex, take each one's client IP, mask it to a prefix, count, and print
the busiest prefixes.

```
./search-cidr.sh [-n N] [-m BITS] [-M BITS] [-i] [REGEX] [LOGFILE]
```

| Flag | Meaning | Default |
| --- | --- | --- |
| `-n N` | how many prefixes to print | `20` |
| `-m BITS` | IPv4 prefix length, `0`–`32` | `24` — `a.b.c.0/24` |
| `-M BITS` | IPv6 prefix length, `0`–`128` | `64` |
| `-i` | match the regex case-insensitively | off |
| `REGEX` | ERE matched against the **whole raw line**, like `grep -E`. Omit (or `""`) for every line | — |
| `LOGFILE` | the log to read | `logs/fc-us.hobbyray.com-frontcache-requests.log` |

`-h` prints the same table. `.gz`, `.bz2` and `.xz` logs are read without unpacking them first.

The regex is matched against the raw line, so **anything in the line is a filter** — there are
no per-field flags to learn, and the columns are positional and stable
([parsed fields](../examples/log-analytics/README.md#parsed-fields) names every one of them):

| You want | Regex |
| --- | --- |
| client requests only, not `<fc:include>` fragments | `toplevel` |
| requests a `bots.conf` rule classified | `no-js-proof` — the rule's **name** is logged |
| one path | `/login\.htm` |
| POSTs | `^[^ ]+ [^ ]+ [^ ]+ POST ` — three fields, then the method |
| cache misses | `cacheable dynamic` |
| a user agent | `-i "python-requests"` |

Combine with `.*` in line order: `"toplevel.*no-js-proof"` is a top-level request that the
`no-js-proof` rule classified.

---

## 3. Reading the output

```
#    CIDR                                 REQUESTS    SHARE      IPS     TRAFFIC  USER-AGENT
1    82.38.96.0/24                              34    0.27%        1    635.8 KB  Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537...
2    202.46.62.0/24                             15    0.12%       14    269.6 KB  Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537...
```

| Column | Is |
| --- | --- |
| `CIDR` | the masked prefix, printed at the mask you asked for |
| `REQUESTS` | matched lines from that prefix |
| `SHARE` | of all matched lines that had a usable client IP |
| `IPS` | **distinct addresses** seen inside the prefix |
| `TRAFFIC` | bytes served, summed from the length column (`-1`/unknown counts as 0) |
| `USER-AGENT` | the first agent seen from that prefix, truncated — a hint, not a summary |

**`IPS` is the column that decides what you are looking at**, and it is the one a per-address
ranking cannot show you. Both rows above are the same order of magnitude in requests and mean
completely different things:

- **`IPS` = 1** — one host, 34 requests. A single machine. If it needs a rule at all, the rule
  can name the address.
- **`IPS` = 14** — fifteen requests spread over fourteen addresses in one `/24`. No single
  address is remarkable, which is exactly why the per-IP view missed it. This is a pool, and the
  prefix is the only thing that describes it.

A prefix whose `IPS` count keeps climbing as you widen the mask is a rotating pool. One where it
stays at 1 is a host that happens to live in a big network — do not block its neighbours.

The footer gives you the denominator:

```
matched 12758 lines: 12758 requests from 10609 distinct prefixes, 208.0 MB served
top 8 prefixes: 114 requests (0.89% of matched), 51 distinct IPs
```

10609 prefixes for 12758 requests is *diffuse* — this filter selected ordinary visitor traffic,
and no rule is going to help. Compare with §4.2, where 231 requests come from 2 prefixes.

---

## 4. Recipes

### 4.1 Who is behind a classification rule

`bots.conf` logs the **name of the rule that decided** in the request log, so a rule name is a
ready-made filter. `no-js-proof` (the `request-type:toplevel ; !cookie:hruc` recipe from the
[2.9.0 notes](release-notes/2.9.0.md)) marks top-level requests from clients carrying no cookie:

```bash
./search-cidr.sh -n 8 -m 24 "toplevel.*no-js-proof"
```

```
#    CIDR                                 REQUESTS    SHARE      IPS     TRAFFIC  USER-AGENT
1    82.38.96.0/24                              34    0.27%        1    635.8 KB  Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537...
2    202.46.62.0/24                             15    0.12%       14    269.6 KB  Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537...
3    144.124.192.0/24                           12    0.09%       12    191.5 KB  Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537...
4    213.230.92.0/24                            12    0.09%       10    156.6 KB  Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537...
5    84.54.70.0/24                              12    0.09%       11    172.4 KB  Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537...
6    2a03:2880:24ff:45::/64                     10    0.08%        1    191.7 KB  Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537...

matched 12758 lines: 12758 requests from 10609 distinct prefixes, 208.0 MB served
top 8 prefixes: 114 requests (0.89% of matched), 51 distinct IPs
```

The reading: `no-js-proof` is doing what it was written to do, and that is **not** a rule you
want to escalate. 12758 requests across 10609 prefixes is the shape of the open internet — first
visits, privacy modes, anything that arrives before a cookie exists. The top prefix is 0.27% of
it. Keep the rule where a dry-run classification belongs and act on nothing here.

To see which rule names are worth filtering on at all, count the column directly:

```bash
awk -F'"' '{print $6}' logs/*-frontcache-requests.log | sort | uniq -c | sort -rn | head
```

### 4.2 What a rule is already rejecting

Guard actions land in `frontcache-failed-requests*.log`, with the rule name and the status
appended. The client-IP column is in the same place, so the script reads that log unchanged —
point it at the file and filter by rule name:

```bash
./search-cidr.sh -n 5 -m 24 "login-alibaba-all" logs/fc-us.hobbyray.com-frontcache-failed-requests.log
```

```
#    CIDR                                 REQUESTS    SHARE      IPS     TRAFFIC  USER-AGENT
1    47.79.11.0/24                              35   15.15%       20       0.0 B  Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebK...
2    47.79.10.0/24                              24   10.39%       19       0.0 B  Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebK...
3    47.82.14.0/24                              15    6.49%       12       0.0 B  Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537...
4    47.82.15.0/24                              15    6.49%        9       0.0 B  Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebK...
5    47.79.15.0/24                              15    6.49%       12       0.0 B  Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537...

matched 231 lines: 231 requests from 28 distinct prefixes, 0.0 B served
top 5 prefixes: 104 requests (45.02% of matched), 72 distinct IPs
```

Three things to read here, in order:

1. **28 prefixes, 231 requests, no prefix over 15%.** Every address is small. This is a rented
   fleet, and a rule naming any single one of them would have been obsolete on deploy.
2. **`TRAFFIC` is 0.** Rejected requests log `-1` bytes — the rule is working, nothing was
   served. On the request log this column is real.
3. **Two user agents, both plausible desktop browsers, across 72 hosts.** The agent string is
   worthless as a discriminator here; the network is the only honest signal.

Point the same query at the request log to see what the rule is *not* catching — traffic from
those ranges that is still being served.

### 4.3 Widen until the blocks stop splitting

28 `/24`s that share their first two octets are one network wearing 28 hats. Widen the mask and
they collapse:

```bash
./search-cidr.sh -n 5 -m 16 "login-alibaba-all" logs/fc-us.hobbyray.com-frontcache-failed-requests.log
```

```
#    CIDR                                 REQUESTS    SHARE      IPS     TRAFFIC  USER-AGENT
1    47.82.0.0/16                              123   53.25%       86       0.0 B  Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537...
2    47.79.0.0/16                              108   46.75%       85       0.0 B  Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebK...

matched 231 lines: 231 requests from 2 distinct prefixes, 0.0 B served
top 2 prefixes: 231 requests (100.00% of matched), 171 distinct IPs
```

**Two prefixes, 100% of the traffic, 171 distinct hosts.** That is the answer the `/24` view was
one step away from: 171 addresses, 2 networks, 1 actor.

The method, which is the whole technique:

1. Start at the default `/24`.
2. If the top rows share leading octets, widen (`-m 20`, `-m 16`) and run again.
3. Stop when `REQUESTS` stops consolidating — when a wider mask only adds neighbours instead of
   merging the rows you already had.
4. **Then check who owns it** (`whois 47.82.0.0`) before writing anything. A `/16` is 65k
   addresses. The script tells you where traffic came from; it cannot tell you that the whole
   range belongs to the same tenant, and the consequence of guessing is blocking a stranger.

Stopping early is the safer error. Two `/17`s that cover your traffic beat one `/16` that also
covers somebody else's customers.

### 4.4 IPv6 needs its own mask

`/24` is meaningless for IPv6 — the mask is `-M`, separately, and `/64` (one customer site) is
the default. At `/32` you are looking at a provider allocation:

```bash
./search-cidr.sh -n 8 -m 16 -M 32 "toplevel.*no-js-proof"
```

```
1    2a03:2880::/32                            647    5.07%      289     10.1 MB  Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537...
2    169.224.0.0/16                             99    0.78%       99      1.4 MB  Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537...
3    2a01:e0a::/32                              54    0.42%       49    977.6 KB  Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537...
```

`2a03:2880::/32` — 647 requests from 289 addresses — is Meta's range, and it is the largest
single source in a filter that looked completely diffuse at `/24`. This is the general case:
**a fleet on IPv6 disappears at a narrow mask**, because it has a practically unlimited supply
of `/64`s and no reason to reuse one. If a query looks like noise, re-run it with `-M 32` before
concluding there is nothing there.

Both families are counted in one pass and ranked in one table, so `-m` and `-M` should be set to
comparable units — `/24` and `/64` (site to site), or `/16` and `/32` (network to network).
Mixing `/16` with `/64` ranks a whole ISP against one household.

### 4.5 Anything the line contains

```bash
# who is walking the catalogue
./search-cidr.sh -m 16 'GET .*\.htm"'

# one expensive uncacheable path
./search-cidr.sh -m 20 '/search\.htm'

# POSTs to the login form
./search-cidr.sh '^[^ ]+ [^ ]+ [^ ]+ POST .*/login\.htm'

# a specific crawler, case-insensitively
./search-cidr.sh -i -m 16 "claudebot"

# every 429 the node sent
./search-cidr.sh -m 16 '" 429$' logs/*-frontcache-failed-requests.log
```

`\S`, `\s`, `\d` and `\w` are **GNU extensions** and are not portable — under macOS's stock
`awk` they match nothing at all, silently, and you get an empty result that looks like an answer.
Write POSIX classes instead: `[^ ]` for `\S`, `[0-9]` for `\d`, `[[:space:]]` for `\s`.

Quote the regex in **single** quotes when it contains a backslash — the script passes it to
`awk` through the environment rather than `-v`, precisely so `\.` survives the trip and means a
literal dot.

---

## 5. From a prefix to a rule — `bots.conf` and `guard-rules.conf`

As of **2.9.0**, `client-ip:` takes an address or a CIDR and works in both rules files:

```
# guard-rules.conf - reject a rented fleet on the path it is abusing
login-alibaba-all | client-ip:47.79.0.0/16 ; uri~/login\.htm | reject:429 Too Many Requests | dry-run
```

```
# bots.conf - classify a range as bot, which changes its TTL rather than refusing it
cloud-fleet | client-ip:47.82.0.0/16 | bot
```

Which file you reach for is the real decision, and it is not about severity:

| You want | File | Effect |
| --- | --- | --- |
| the range served from cache with a bot TTL | `bots.conf` | a **behaviour profile** — nothing is refused |
| the range refused, redirected, or rate-limited | `guard-rules.conf` | an **action**, before cache and origin |

[guard-getting-started.md](guard-getting-started.md) owns the grammar of both — conditions,
actions, ordering, and the exemption-first idiom. Note that classification is *not* a judgement
about a client; it selects a TTL profile, and the [2.9.0 notes](release-notes/2.9.0.md) explain
why there are exactly two values.

**Ship it `dry-run` first.** The rule is evaluated and logged, and the request is left alone.
Then re-run this script against the failed-requests log filtering on the rule's own name, and
you are reading the exact traffic the rule would have acted on — §4.2 is that loop closed.
[guard-getting-started.md §6](guard-getting-started.md) is the rollout procedure.

**More than two or three ranges belong in a file.** `client-ip:@bot-networks/aws.txt` reads one
address or CIDR per line from a file under `conf/`, so a cron job can refresh a published range
list without touching the rules:

```bash
./search-cidr.sh -n 50 -m 16 "<your filter>" \
  | awk '$1 ~ /^[0-9]+$/ && $5 >= 5 { print $2 }' > /tmp/candidates.txt
```

That prints the prefixes with at least 5 distinct hosts — candidates, not a blocklist. `whois`
each one and keep the ones you can attribute. `action=reload-bots` and
`action=reload-guard-rules` re-read both files without a restart.

**Rate-limit before you block, when the traffic is merely fast.** A `rate:` condition on a
`client-ip:` range slows a crawler you want to keep — see
[rate-limit-howto.md](rate-limit-howto.md), §4.4 there for exactly that case. Blocking is for
traffic you have decided has no legitimate use.

---

## 6. The address the script reads, and the address the rule matches

**These are two different resolutions, and a rule built from this script's output can miss.**
Read this before you write a `client-ip:` rule from a prefix you found here.

The log's client-IP column is the forwarding chain as it arrived:

```
"49.43.157.201, 172.71.135.93"
 ^ leftmost: what the client wrote   ^ the CDN edge that relayed it
```

- **The script takes the leftmost entry.** That is the convention for "the real client" in a
  chain your edge appended to, and with no comma it takes the whole value.
- **`client-ip:` does not.** It resolves through `ClientIpResolver`, which reads the chain
  **right to left** and believes an entry only when it came from a peer listed in
  `front-cache.client-ip.trusted-proxies`. The leftmost entry is the one part of the header a
  client can forge, so a rule that trusted it could be evaded — or aimed at a third party — by
  anyone who sends a header.

They agree when there is exactly **one trusted hop** in front of the node and nobody forged
anything, which is the ordinary CDN deployment. Check your own chain before trusting the
correspondence:

```bash
awk -F'"' '{n=split($4,a,","); c[n]++} END{for (k in c) printf "%d hops: %d lines\n", k, c[k]}' \
  logs/*-frontcache-requests.log
```

One consistent hop count and you are in the simple case. Mixed counts mean some traffic reaches
you by another path — those rows' leftmost entries are not comparable, and a prefix built from
them is not either.

Two consequences worth stating plainly:

- **With `front-cache.client-ip.trusted-proxies` empty** (the default) the node sees only the
  socket peer, so behind a CDN every `client-ip:` rule matches the *CDN's* address for every
  request — the rule you deploy from these findings will do nothing, or everything. The node
  WARNs at startup when a `client-ip:` rule is loaded and the list is empty. It is the same
  prerequisite `rate:` has, and [guard-getting-started.md §5.1](guard-getting-started.md) is
  where it is configured.
- **Forged `X-Forwarded-For` pollutes this script, not the node.** An attacker can put any
  address in the leftmost position and appear in these results as any network they like. A
  prefix you cannot corroborate — with `whois`, with a hop count, with the traffic continuing
  after you act — may be somebody pointing you at a bystander.

---

## 7. What it actually does

One `awk` pass, no sort subprocess, no temp files:

1. Keep lines matching the regex.
2. Split on `"` and take the client-IP column, then its first comma-separated entry.
3. Mask: IPv4 to `-m` bits, IPv6 to `-M` bits. `::ffff:1.2.3.4` is an IPv4 client and is masked
   with `-m`. A zone id (`%eth0`) is stripped; IPv6 output is compressed per RFC 5952.
4. Accumulate requests, bytes and a distinct-address set per prefix.
5. Partial selection sort — only the top `N` rows are ordered, so the cost does not grow with
   the number of prefixes.

**0.7 s for a 17 MB, 43k-line log** on a laptop, and it is linear: the memory it holds is one
entry per distinct prefix plus one per distinct address, not one per line.

It is **portable `awk`** — no `and()`, no `strtonum()`, no `gensub()`. The bit masking and the
hex parsing are written out by hand precisely so the script runs under macOS's stock `awk`
(BWK), `mawk` and `gawk` alike, which is what lets it run on the log host instead of yours. The
masking was verified against Python's `ipaddress` module across random addresses at every mask
width from 0 to 32 and 0 to 128.

Addresses that cannot be parsed are **counted and reported** in the footer rather than dropped
silently, so a log in an unexpected shape says so instead of quietly returning a short list.

---

## 8. When something does not work

| Symptom | Cause |
| --- | --- |
| `no matching lines` | the regex matched nothing. Check it with `grep -cE "<regex>" <log>` first |
| `no matching lines with a usable client IP` | lines matched but carry no IP column — you pointed it at `error.log` or `fallback.log`; this script reads the request and failed-request logs |
| every prefix has `IPS` = 1 | the mask is too narrow for the family. Widen — and for IPv6 that is `-M`, not `-m` |
| one prefix is ~100% of everything | you are aggregating your own CDN or proxy, not clients. §6 — the chain is not what you think it is |
| `TRAFFIC` is `0.0 B` everywhere | expected on the failed-requests log: rejects log `-1` bytes |
| `skipped N unparsable client IPs` | a chain in an unexpected format. `awk -F'"' '{print $4}' <log> \| sort -u \| head` shows what arrived |
| a `\.` in the regex matches any character | shell quoting — use single quotes around the regex |
| a regex using `\S`, `\d` or `\w` finds nothing | GNU-only escapes. Use `[^ ]`, `[0-9]`, `[A-Za-z0-9_]` — §2 |
| the rule you deployed matches nothing | §6, and `front-cache.client-ip.trusted-proxies` |

---

## 9. What this does not do

- **It does not attribute a range.** A prefix is where packets came from. Whether one tenant
  owns it is a `whois` question, and `whois` is the step between this output and a rule.
- **It does not know about CDNs in front of the client.** Traffic proxied through a third party
  aggregates under the proxy's network, which may be a cloud range you are about to block.
- **It reads a log, so it is history.** It tells you what happened in the window the file
  covers, not what is happening now. For live pressure, `rate:` measures the present
  ([rate-limit-howto.md](rate-limit-howto.md)); for a trend over weeks, the
  [Kibana dashboards](../examples/log-analytics/README.md) hold more than one file.
- **It is not a blocklist generator.** The output is evidence. Every recipe above ends in
  `dry-run` and a human, and §4.3 ends in `whois`, on purpose: the cost of a wrong `/16` is paid
  by people who never visited you.

---

Guard rules: [guard-getting-started.md](guard-getting-started.md) ·
Rate limiting: [rate-limit-howto.md](rate-limit-howto.md) ·
The logs and every parsed field: [examples/log-analytics](../examples/log-analytics) ·
`client-ip:` and `bots.conf` rules: [2.9.0 release notes](release-notes/2.9.0.md) ·
Dashboards: [console-dashboards.md](console-dashboards.md)
