# Debug plugin - edge debugging

`GET /fc-x/debug/` shows a visitor how the node sees **their** request, and what the guard recently decided about
their address:

- the client address the node resolved (through `front-cache.client-ip.trusted-proxies` only), the client type `bots.conf`
  gave it, the scheme, the request id, the method, path and query;
- the headers as a plugin's view shows them (`Authorization`, `Cookie` and `x-frontcache-*` never are);
- the last guard decisions about the visitor's address, newest first - rules that rejected, redirected or challenged
  a request, dry runs, and rate limits the visitor went over - from the node's `GuardMatched`, `RateLimitExceeded`
  and `ChallengeIssued` events.

Manifest: `id = debug`, `kinds = serve, observe`, `config = debug.conf`. It decides nothing: it reads the node's view
of its own requests and the node's events after the fact.

## Who sees it

Only the addresses `conf/debug.conf` allows - by default the node itself. Anyone else, on any path under
`/fc-x/debug/`, gets the `404` a node without the plugin answers, so the page does not say it exists.

```hocon
# conf/debug.conf - read whole or refused whole
allow = ["127.0.0.1/32", "::1/128", "203.0.113.0/24"]   # addresses or CIDR blocks, as the node resolves them
recent = 20                                             # decisions kept per visitor (1..200)
visitors = 1000                                         # visitors kept, least recently seen dropped first
```

Behind a load balancer or nginx, the "client address" is what the node resolved through its trusted proxies - so
`allow` names the operators' own addresses, never the proxy's. A guard rule can protect the path as any other:
`/fc-x/` goes through the guard first.

## Privacy

An event's `clientKey` is the address the guard counted - personal data. The plugin keeps decisions keyed by
`PluginContext.hashClientKey(clientKey)` (a keyed hash, per node and per run, in memory only) and matches the viewer by
hashing their resolved address the same way; it never stores an address. Everything is in memory and bounded
(`recent` x `visitors`), and gone at a restart. The page is HTML-escaped throughout, has no script, and its
`Content-Security-Policy` allows only its own stylesheet (`/fc-x/debug/debug.css`).

## Build and install

```sh
./gradlew build   # Java 25, Gradle 9.7; -PfrontcacheMavenLocal against an unreleased core
```

Put `build/libs/frontcache-plugin-debug-0.1.0.jar` in `FRONTCACHE_HOME/plugins/debug/`, then in
`conf/frontcache.properties`:

```properties
front-cache.plugins = debug
front-cache.plugins.debug.sha256 = <./gradlew sha256>
```

and restart the node. The class-loader rule, the testkit and the rest are as in the plugin template
(`examples/plugin-template/README.md`). In the core's build the plugin's tests run as part of
`./gradlew :frontcache-plugin-testkit:test`.
