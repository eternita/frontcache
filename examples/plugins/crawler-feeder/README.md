# Crawler feeder - verified crawler lists

Keeps `conf/lists/googlebot.txt`, `bingbot.txt` and `applebot.txt` current, so a rule can tell the real crawler
from a scraper that only borrows its name. A list holds two things:

- **the ranges the vendor publishes** - fetched daily from Google's, Bing's and Apple's JSON;
- **claimants confirmed by reverse DNS** - an address that says it is the crawler, is not in those ranges, and
  checks out the way the vendors document: its reverse DNS name is under the crawler's domain
  (`crawl-66-249-66-1.googlebot.com`), and that name resolves back to the address.

```
# conf/guard-rules.conf
# the real crawler is exempt from the limits below
googlebot-ok    | ua~(?i)googlebot ; client-ip:@lists/googlebot.txt  | allow
# a claimant from outside the list: logged, and reported to the feeder, which checks it
googlebot-claim | ua~(?i)googlebot ; !client-ip:@lists/googlebot.txt | reject:403 | dry-run

# conf/bots.conf - a "Googlebot" from outside the list is classified as a bot like any scraper
fake-googlebot  | ua~(?i)googlebot ; !client-ip:@lists/googlebot.txt | bot
```

Manifest: `id = crawler-feeder`, `kinds = observe, manage`, `config = crawler-feeder.conf`. It decides nothing: it
fetches on the node's `PluginContext.scheduler()`, checks claimants on a thread of its own, and writes through
`PluginContext.writeList` - the core's `client-ip:` rule decides, and sees a new list at once, without a reload.

## Claimants

A **claim rule** is any guard rule the feed names - `<feed>-claim` unless `rules` says otherwise. Each time it
matches (as a dry run, or acting), the node's `guard.matched` event carries the address it resolved for the
request (behind `front-cache.client-ip.trusted-proxies`, the visitor's, not the proxy's), and the feeder checks
that address once:

- **confirmed** - on the list for `keep` (7 days), checked again from half-way through; a check that confirms
  renews it, one that finds another name takes it off at once, one that gets no answer leaves it until `keep` runs
  out. At most `max-verified` (1,000) per list - the oldest confirmation gives way.
- **not confirmed** - not looked up again for `retry` (1 hour), whatever the reason (no name, a name elsewhere, a
  name that does not resolve back, a resolver that does not answer).

What that means for a rule:

- **The check is after the fact.** A claimant's first request (and those in the few lookups' time after it) is
  decided as an unlisted address's is - which is why the claim rule above is a dry run, and the bots.conf rule
  only calls it a bot. A `reject:403` claim rule refuses a genuine crawler from a new range until it is confirmed.
- **Only a public address is looked up** - loopback, private, link-local, unique-local, CGNAT and multicast
  addresses are counted as `ignored`. A node behind a proxy it does not trust sees every visitor as the proxy.
- **A claimant's address is personal data** until it is confirmed (then it is a crawler's): an unconfirmed one is
  never in status or at INFO; the debug log names it by the node's `hashClientKey`.
- **No lookup on the request path, and no unbounded queue**: the listener only queues (1,000 waiting at most; more
  are dropped and counted), one thread looks up.
- The resolver is the JDK's - the node's `/etc/resolv.conf` and the JVM's DNS cache - and needs no module beyond
  `java.base` (the bundles' runtime has no `jdk.naming.dns`).

## Config

```hocon
# conf/crawler-feeder.conf - read whole or refused whole. Absent: these three feeds, daily, each checking claimants.
feeds = [
  { name = googlebot, url = "https://developers.google.com/static/search/apis/ipranges/googlebot.json", every = 24h,
    domains = [googlebot.com, google.com] },
  { name = bingbot,   url = "https://www.bing.com/toolbox/bingbot.json", domains = [search.msn.com] },
  { name = applebot,  url = "https://search.developer.apple.com/applebot.json", domains = [applebot.apple.com] },
  # a crawler that publishes no ranges: a list of confirmed claimants only
  { name = yandexbot, domains = [yandex.ru, yandex.net, yandex.com], rules = [yandexbot-claim] }
]
timeout = 30s        # connecting, and the whole answer
keep = 7d            # 1h..90d
retry = 1h           # 1m..7d
max-verified = 1000  # per list, 1..10000
```

- `name` is the list: `conf/lists/<name>.txt` (lower case letters, digits, `.`, `_`, `-`).
- `url` is `https` - plain `http` only to a loopback host. A feed is `{"prefixes":[{"ipv4Prefix":"..."},
  {"ipv6Prefix":"..."}]}`, the format all three vendors use. Without `url`, a feed needs `domains`, and its list is
  written at start (empty) so a rule reading it finds a file.
- `every` is 1m..30d, and only with a `url`. A restart does not fetch again before a feed is due (each feed's last
  success and its prefixes are kept in `plugins/crawler-feeder/data/`); a changed `url` is fetched at once.
- `domains` - the names a claimant's reverse DNS must be under, two labels at least (`googlebot.com` admits
  `crawl-1.googlebot.com`, not `googlebot.com` itself nor `evilgooglebot.com`). A feed written out without
  `domains` checks no claimant. Changing them takes off the list every claimant whose name is no longer under one.
- `rules` - the guard rules that report a claimant; `[<name>-claim]` when left out, and only with `domains`.
- HOCON: separate the feeds with a comma or a newline - two objects side by side on one line are *merged*.

The plugin observes `guard.matched` only while some feed has `domains`.

## What it never does

- **Write an empty or a partial list.** A fetch that fails (connect error, timeout, a status other than 200 - a
  redirect is not followed), a body over 4 MiB, an answer that is not that JSON, one with no prefix, or one with a
  single entry that is not an address or a CIDR block (`writeList` refuses a list whole) keeps the list as it was,
  and the reason shows in `crawler-feeder-status`. A list of claimants is never written without its feed's ranges:
  a confirmation before the first fetch waits for it.
- **Overwrite a list it does not manage.** `writeList` writes `# managed by plugin crawler-feeder` as the first
  line, and never replaces a file without it - an operator's own `lists/googlebot.txt` stays theirs (the feed then
  reports the refusal, `lastError` for a fetch, `writeError` for a confirmation).
- **Put a line of its own into a list from DNS.** A reverse DNS name is written as a comment only once it is a
  plain host name under the feed's domains.
- **Use a library the node does not show a dropped-in plugin**: it reads JSON with a small parser of its own
  (`Json`), not jackson-databind, and fetches with `java.net.http.HttpClient`.

Removing the plugin leaves the lists as they were: delete `conf/lists/<name>.txt` too.

## Management actions

- `crawler-feeder-status` - each feed: its list, url, last attempt and success, address blocks, last error,
  consecutive failures, seconds to the next fetch; its domains, rules, confirmed claimants (the newest 20, with
  their names and until when); and the checks' counters (`claims`, `lookups`, `confirmed`, `refuted`, `unknown`,
  `dropped`, `ignored`, `pending`).
- `crawler-feeder-refresh[&feed=<name>]` - fetch now (all feeds with a url, or one). Write-gated: it rewrites
  `conf/lists`.
- `crawler-feeder-verify&address=<ip>[&feed=<name>]` - check an address now, against every feed with domains (or
  one), and list it if it is confirmed. Write-gated; the caller waits for the lookups.

## Build and install

```sh
./gradlew build   # Java 25, Gradle 9.7; -PfrontcacheMavenLocal against an unreleased core
```

Put `build/libs/frontcache-plugin-crawler-feeder-0.1.0.jar` in `FRONTCACHE_HOME/plugins/crawler-feeder/`, then in
`conf/frontcache.properties`:

```properties
front-cache.plugins = crawler-feeder
front-cache.plugins.crawler-feeder.sha256 = <./gradlew sha256>
```

and restart the node. Its tests run the feeds against a loopback HTTP server and the checks against a resolver of
their own - no network. In the core's build they run as part of `./gradlew :frontcache-plugin-testkit:test`.
