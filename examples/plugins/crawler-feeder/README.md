# Crawler feeder - verified crawler lists

Keeps `conf/lists/googlebot.txt` and `conf/lists/bingbot.txt` current from the address ranges Google and Bing
publish, so a rule can tell the real crawler from a scraper that only borrows its name:

```
# conf/bots.conf - a "Googlebot" from outside Google's ranges is classified as a bot like any scraper
fake-googlebot | ua~(?i)googlebot ; !client-ip:@lists/googlebot.txt | bot
# conf/guard-rules.conf - or refused outright
fake-googlebot | ua~(?i)googlebot ; !client-ip:@lists/googlebot.txt | reject:403
```

Manifest: `id = crawler-feeder`, `kinds = manage`, `config = crawler-feeder.conf`. It decides nothing: it fetches on
the node's `PluginContext.scheduler()` and writes through `PluginContext.writeList` - the core's `client-ip:` rule
decides, and sees a new list at once, without a reload.

## Config

```hocon
# conf/crawler-feeder.conf - read whole or refused whole. Absent: these two feeds, daily.
feeds = [
  { name = googlebot, url = "https://developers.google.com/static/search/apis/ipranges/googlebot.json", every = 24h },
  { name = bingbot,   url = "https://www.bing.com/toolbox/bingbot.json", every = 24h }
]
timeout = 30s   # connecting, and the whole answer
```

- `name` is the list: `conf/lists/<name>.txt` (lower case letters, digits, `.`, `_`, `-`).
- `url` is `https` - plain `http` only to a loopback host. A feed is `{"prefixes":[{"ipv4Prefix":"..."},
  {"ipv6Prefix":"..."}]}`, the format both vendors use.
- `every` is 1m..30d. A restart does not fetch again before a feed is due (each feed's last success is kept in
  `plugins/crawler-feeder/data/state.properties`); a changed `url` is fetched at once.
- HOCON: separate the feeds with a comma or a newline - two objects side by side on one line are *merged*.

## What it never does

- **Write an empty or a partial list.** A fetch that fails (connect error, timeout, a status other than 200 - a
  redirect is not followed), a body over 4 MiB, an answer that is not that JSON, one with no prefix, or one with a
  single entry that is not an address or a CIDR block (`writeList` refuses a list whole) keeps the list as it was,
  and the reason shows in `crawler-feeder-status`.
- **Overwrite a list it does not manage.** `writeList` writes `# managed by plugin crawler-feeder` as the first
  line, and never replaces a file without it - an operator's own `lists/googlebot.txt` stays theirs (the feed then
  reports the refusal).
- **Use a library the node does not show a dropped-in plugin**: it reads JSON with a small parser of its own
  (`Json`), not jackson-databind, and fetches with `java.net.http.HttpClient`.

## Management actions

- `crawler-feeder-status` - each feed: its list, last attempt and success, address blocks, last error, consecutive
  failures, seconds to the next fetch.
- `crawler-feeder-refresh[&feed=<name>]` - fetch now (all feeds, or one). Write-gated: it rewrites `conf/lists`.

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

and restart the node. Its tests run the feeds against a loopback HTTP server - no network. In the core's build they
run as part of `./gradlew :frontcache-plugin-testkit:test`.
