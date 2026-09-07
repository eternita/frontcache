# Intro video — narration script

Source of truth for both outputs of the intro explainer:

- **A. Interactive page** — [`index.html`](index.html) in this directory (primary).
- **B. Narrated MP4** — the same scenes on a fixed timeline, narration synthesized from the text
  below (secondary; see [README.md](README.md) §"Rendering the MP4").

The plan behind it is `docs/intro-vidio-plan.md` in the development repository. This file is the
artifact its §8.1 asks for: full narration text, per scene, reviewed against the numbers policy
(§5) *before* animation. **Every number spoken here is traceable to a file in this repository**
— the "Source" line of each scene says which, and §"Numbers" at the bottom lists them all with
the caveat each one must carry.

Voice: one synthesized voice, kept across this video and the follow-up, at ~150 wpm. Every
scene's narration has to **fit inside the duration authored in `index.html`** (`SCENES[].dur`) —
the visuals are the fixed constraint, and the render pipeline measures the synthesized audio
against them rather than trusting a word count — the local voice measures 140 wpm at speed
1.0, so the render synthesizes at **speed 1.08** (~151 wpm). Total **5:30** at seven scenes.

| # | Scene | Runs | Interactive |
|---|---|---|---|
| 1 | What is in the request path | 0:00–0:35 | |
| 2 | A page is fragments, not a page | 0:35–1:25 | yes |
| 3 | 1st hit, 2nd hit, N hits | 1:25–2:20 | yes |
| 4 | Invalidation | 2:20–3:00 | yes |
| 6 | Guard: refuse it before it costs anything | 3:00–3:45 | |
| 7 | One host, then the world | 3:45–4:35 | |
| 8 | What it costs the origin | 4:35–5:30 | |

**Scene 5 (combine & reduce) is deliberately absent and keeps its number**, so that when its
measurement lands it drops in between scenes 4 and 6 without renumbering, re-narrating or
re-cutting anything around it. Plan §7.3.

---

## Scene 1 — What is in the request path (0:00–0:35)

> A browser asks your app for a page. Your app renders it: templates, a database, a search
> index. Forty-one milliseconds, median.
>
> Now put Frontcache in front of it. Same request, same bytes back — one point six milliseconds,
> because your app never ran.
>
> The rest of this video is how. Six lanes: the client, the guard, the cache — memory then disk,
> the include processor, your origin, and what your origin talks to. The colours are the words
> Frontcache writes in its own logs.

- **On screen:** 41.3 ms → 1.6 ms; caption "median · measured A/B on 100,000 replayed production requests"; the six lanes named once; the colour legend.
- **Source:** [value.md §3](../value.md) (41.3 ms → 1.6 ms p50), [concept.md §1](../concept.md).
- **Caveat, on screen for the whole beat:** *median*, and *100,000 replayed production requests, 8 concurrent, one app*.

---

## Scene 2 — A page is fragments, not a page (0:35–1:25) · interactive

> Here is the objection every team has: our pages are personalized, so they cannot be cached.
>
> A page is not one thing. This storefront is a header, a nav, a category body, a product grid, a
> recommendations rail, a cart badge and a footer — and they do not expire together. Your origin
> says so per fragment, in response headers.
>
> The cart badge is the personalized part. It is marked never-cache, and it stays amber for the
> rest of this video.
>
> So the origin returns the outer document with include markers, and the edge fills them in,
> concurrently. An async fragment is fired and forgotten: nothing is waited for, nothing is
> inserted.
>
> One dynamic fragment does not make the page uncacheable. It makes one tile amber.

- **On screen:** the wireframe splitting along `fc:include` seams; label cards `maxage`, `tags`, `client=bot|browser`, `call=async`; the cart badge amber with `maxage=0` + `dynamic-urls.conf`.
- **Interactive:** the viewer toggles any tile between cached and dynamic and watches the per-request origin-render count respond.
- **Source:** [concept.md §5–§6](../concept.md), [http-headers.md](../http-headers.md), [jsp-tags.md](../jsp-tags.md).
- **Markup is generic on purpose** (`example.com`, a storefront). Plan §5, §7.4.

---

## Scene 3 — 1st hit, 2nd hit, N hits (1:25–2:20) · interactive

> The same page, three times. These are three different physical events.
>
> First hit. Nothing is cached. Every fragment goes to the origin, the origin renders, the database
> is asked, and every response is written to both cache tiers on the way back. This is the
> expensive one.
>
> Second hit. Every cacheable fragment is answered from L1 — memory, in the same process. The
> origin lane is silent except the cart badge. The median collapses.
>
> Now the thousandth hit, where real traffic lives. A fragment pushed out of memory is still on
> disk, in the Lucene index — slower than L1, nowhere near your origin. And when one expires, soft
> refresh serves the stale copy immediately and revalidates behind it.
>
> None of this is narrative. Every state is a header on the response and a line in the log.

- **On screen:** three passes over the identical lane diagram; the cache-state panel; the console realtime-monitor cutaway (2 s, redacted).
- **Interactive:** "Send request", plus "expire a fragment" and "drop L1" — the viewer chooses which lane answers.
- **Source:** [`docs/diagrams/02-request-lifecycle.svg`](../diagrams/02-request-lifecycle.svg), [concept.md §3, §5](../concept.md) (`x-frontcache-component-cache-level`, `x-frontcache-component-refresh: soft`).

---

## Scene 4 — Invalidation (2:20–3:00) · interactive

> A price changes. Cached pages are only useful if you can clear exactly what changed.
>
> By URL pattern: one call, a regular expression, and the entries whose own keys match are gone. Or
> by tag: every fragment your origin stored with that product's tag, on every page that includes
> it. URL patterns cut down the site; tags cut across it.
>
> One call fans out to every node in a cluster, which is the next scene.
>
> And the honest bit: invalidate with a filter of star, and you have emptied the whole node.

- **On screen:** `agent.removeFromCache("/store/product-details-42.*")` beside `x-frontcache-component-tags: product-42`; three pages sharing one fragment; the cluster fan-out; the `filter=*` blast radius.
- **Interactive:** the viewer picks URL vs tag and sees which tiles go red.
- **Source:** [concept.md §9](../concept.md), [deployment-usecases.md §2.2 / §3.2](../deployment-usecases.md), [security.md](../security.md) (blast radius).

---

## Scene 6 — Guard: refuse it before it costs anything (3:00–3:45)

> Some traffic should not reach your cache, let alone your app.
>
> The guard runs before both. A scanner walking your server by IP address is not a visitor. A
> logged-out client on an account-only page would render at your origin only to be told to log in.
> And a client asking faster than your limit gets a 429 with a Retry-After.
>
> Two things to learn here rather than the hard way. Exemptions go first: your health check and
> your management API are addressed by IP too. And behind nginx, rate limiting counts nothing until
> you name your trusted proxies.
>
> Ship every rule with dry-run first.

- **On screen:** the guard lane turning three dots away, cache and origin greyed out behind it; the rules file with its exemptions on top; the 429 with `Retry-After` and `Cache-Control: no-store`; the same rule with `| dry-run` and a hit counter.
- **Source:** [guard-getting-started.md](../guard-getting-started.md) §3, §4, §5, §5.1, §5.3, §6.
- **The rules on screen are the shipped documentation's own examples** (`ip-flood | rate:100/10s`, `search-flood | uri~^/search\.htm ; rate:20/10s`), genericised hostnames, no invented thresholds. Plan §5.

---

## Scene 7 — One host, then the world (3:45–4:35)

> Where does this go in your infrastructure? Three answers, and they are the same engine.
>
> One: a servlet filter inside your app's own JVM. One JAR, one filter registration, response
> headers. No new tier, no new host.
>
> Two: a standalone reverse proxy in front of an origin written in anything — PHP, Node, Python,
> Java. Your app does not know what is in front of it.
>
> Three: geographic routing to regional edges, in front of an origin that runs the filter itself.
> Two cache tiers, and the second matters: when several edges miss at once, the origin's own filter
> stops that arriving as a stampede.
>
> You start at one, and you arrive at three without redesigning anything.

- **On screen:** the three topologies morphing rather than cutting, built on [`03-filter-topology.svg`](../diagrams/03-filter-topology.svg), [`04-standalone-topology.svg`](../diagrams/04-standalone-topology.svg), [`06-gslb-topology.svg`](../diagrams/06-gslb-topology.svg) and [`07-multiregion-sequence.svg`](../diagrams/07-multiregion-sequence.svg).
- **Source:** [deployment-usecases.md](../deployment-usecases.md).
- **Not named here:** the production deployment that runs topology 3. Plan §7.4 moves it, with its numbers, to the follow-up video.

---

## Scene 8 — What it costs the origin (4:35–5:30)

> One hundred thousand requests, replayed from a production log against one application. Twice:
> cache off, then cache on. Same concurrency, same bytes served.
>
> Requests that reached the origin: seventy-nine thousand nine hundred, against one thousand and
> three. Ninety-eight point seven percent fewer. Throughput six point one times higher, median
> forty-one point three milliseconds down to one point six — and the application was not changed.
>
> Two honesty notes, because they are why the rest is trustworthy. The maximum more than doubled,
> and p99 gained least, because six times more traffic queues on the small uncached remainder.
>
> And that ninety-nine percent hit ratio is a hot working set. Over the full log, the same harness
> measures about sixty-seven percent.
>
> Don't take the number — replay your own access log against your own app, and get your own.

- **On screen:** two counters racing to 100,000 (79,900 vs 1,003); the results table; the two caveats as their own beat; the second panel (app tier at 1.3%, and what that means below it); the closing CTA.
- **Source:** [value.md §3, §4, §6](../value.md), [`benchmark/`](../../benchmark).
- **Closing CTA, in this order:** run the Docker one-liner · replay your own log with `benchmark/` · read [value.md](../value.md) · [licensing](https://www.eternita.co/frontcache.html).

---

## Numbers

Every figure spoken or shown, its source, and the caveat it must carry (plan §5).

| Number | Where it is | Source | Required caveat |
|---|---|---|---|
| p50 41.3 ms → 1.6 ms | scenes 1, 3, 8 | [value.md §3](../value.md) | *median*; 100,000 replayed production requests, 8 concurrent, one app |
| 79,900 → 1,003 origin renders (−98.7%) | scene 8 | value.md §3 | same run |
| 6.1× throughput (172.4 → 1054.0 req/s) | scene 8 | value.md §3 | at *fixed* concurrency — a floor on headroom, not a ceiling (value.md §6) |
| 7.26 GB transferred by both runs | scene 8 | value.md §2 | it is what "byte-identical content" means here |
| max 295.2 ms → 775.4 ms (worse); p99 −42%, the smallest gain | scene 8 | value.md §3, §6 | keep it — it buys the rest their credibility |
| 98.74% hit ratio / ~66.7% over the full 681k log | scene 8 | value.md §3, §6 | always shown as a *range*, never 98.74% alone as a promise |
| app tier serves 1.3% | scene 8 | value.md §3 (1,003 / 79,899) | arithmetic on the row above, same caveats |

**Not used anywhere:** any "typical customer sees X" figure (there isn't one — value.md §6), any
volume from the live deployment (plan §7.4), and any modelled arithmetic — which is why scene 5
is held (plan §7.3).

**A note on one number, since it was wrong in the source until now.** [value.md §6](../value.md)
used to say in prose "the p99 and max got worse", while its own §3 table showed p99 *improving*
208.1 → 120.3 ms (−42%) and only *max* getting worse (295.2 → 775.4 ms). value.md's prose has
been corrected to match its table; this script says **max**, and adds the honest second half —
p99 gained least of any percentile (−42%, against −96% at p50), for the same reason.
