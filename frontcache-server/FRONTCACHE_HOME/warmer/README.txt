FRONTCACHE_HOME/warmer - URI lists for the cache warmer
=======================================================

Every *.txt file directly in this directory is a URI list the cache warmer can run: from the
console (Cache > Cache Warmer), or with the management API (action=warmer-start). dump-keys writes
here too, as keys_<yyyyMMdd_HHmmss>.txt - the list of exactly what this node was caching, which is
the best list to warm a replacement node from.

A run sends each entry to this node's OWN front door (http://127.0.0.1:<port>), so each page is
fetched and cached exactly as a visitor's request would be - same cache key, same TTL, same
includes - paced by a delay between calls and a concurrency limit, both changeable mid-run.

List format - one entry per line, UTF-8:

    # comments and blank lines are ignored
    /
    /category/shoes?page=2
    http://www.example.com/p/123      # absolute URLs work; only the path and query are used

Only the path and query of an entry are used. The host every entry is stored under is the run's
KEY BASE (front-cache.warmer.key-base, or <scheme>://<front-cache.default-domain>) - it must be what
visitors' requests arrive with, or the run "succeeds" and nothing it warmed is ever read. The
console's Check shows the keys the first entries will be stored under before anything is sent.

Names: letters, digits, '.', '_' and '-', ending in .txt, at most 104 characters.

What a run leaves behind: <list>.failed-<yyyyMMdd_HHmmss>.txt, holding the entries that failed or
were not cacheable, each with its reason as a trailing comment - itself a list, so it can be run
again once the cause is fixed. .state/run.json is the checkpoint a stopped or interrupted run can be
resumed from; resuming is never automatic.

warmer.sh is the old curl loop. It is superseded by the above: it has no pacing, reports nothing,
sends every request to the public hostname (so through the CDN, to whichever node it picks), and
takes its client type from a Googlebot User-Agent that bots.conf may no longer believe.
