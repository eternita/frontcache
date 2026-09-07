# Include combining — N fragments, one origin call

Concepts: [concept.md](concept.md) · Includes: [concept.md §6](concept.md#6-fragments-and-includes) ·
JSP tags: [jsp-tags.md](jsp-tags.md)

New in **2.8.0**.

A page split into `<fc:include>` fragments costs the origin **one request per fragment** whenever
they are not cached. For an origin whose data access is batched at page level — one `IN (…)` per
entity type, one search query for the whole grid — that multiplies backend work by the number of
fragments, because each fragment re-enters the same batch machinery with a set of size one.

Combining lets the edge aggregate the **cache-missing** includes of one page into a single origin
request, and lets the origin answer them together. Each fragment is still cached individually,
under the same key it would have had, with its own `maxage` and its own tags — nothing about
invalidation, expiry or the shape of the cache changes.

---

## 1. The problem, in numbers

A 15-cell search grid, against an origin whose loader issues five queries whatever the size of its
input set (asking it for one id costs what asking it for fifteen costs):

| | Origin requests | Search queries | SQL |
| --- | ---: | ---: | ---: |
| Whole page, no includes | 1 | 1 | **5** |
| 15 cell includes, cold cache | 15 | 15 | **75** |
| 15 cell includes, cold cache, **combined** | **1** | **1** | **5** |
| 15 cell includes, warm cache | 0 | 0 | 0 |
| 15 cell includes, 3 cells new, **combined** | **1** | 1 | **5** |

The 15× row is not a badly written origin. It is the *consequence of batching done right*:
fragmenting the page turns one call with fifteen ids into fifteen calls with one id each.
Combining hands the origin back the set it was designed to receive.

**The cost being attacked is backend queries during cache warm-up**, not HTTP round trips. Round
trips are a side benefit — keep-alive and HTTP/2 already make them cheap. If your handler answers a
batch with a loop over its single-item code path, it pays the same 75 queries and this feature buys
you nothing.

---

## 2. Turn it on

One attribute, on the includes that should be grouped:

```jsp
<fc:include url="/fc/search-item.htm?id=1" combine="true" />
<fc:include url="/fc/search-item.htm?id=2" combine="true" />
<fc:include url="/fc/search-item.htm?id=3" combine="true" />
```

Non-Java origins write the same attribute into the marker they emit:

```html
<fc:include url="/fc/search-item.php?id=1" combine="true"/>
```

With an empty cache the edge issues **one** request carrying all three parameter sets. With `id=2`
already cached it issues one carrying two. With all three cached it issues none — exactly as before.

| `combine` value | Grouped by |
| --- | --- |
| *absent* | not combinable — pre-2.8.0 behaviour, unchanged |
| `true` | the include's path |
| any other string | the path **and** that name |

The named form lets one endpoint serve two semantically different sets on the same page without
merging them (`combine="search-cell"` vs `combine="related-cell"`). Grouping always includes the
path, so a name reused across two paths splits naturally rather than erroring.

### This is opt-in twice, on purpose

It is **not** a transparent edge optimisation. It needs the attribute in your markup *and* a handler
at your origin that understands the request (§4–§6). An origin that has the attribute but not the
handler **renders correctly** — the edge detects it and falls back to individual fetches — and gains
nothing.

---

## 3. What the edge actually does

- **Only cache-missing members are batched.** The cache lookup happens first, per member, exactly as
  before; the batch carries whichever members missed.
- **A group down to one missing member is not batched.** The origin does the same work either way,
  and the ordinary include path reaches it in one hop instead of two. This is the steady state for a
  page whose fragments are nearly all cached.
- **Async includes are never combined.** Nothing waits for a `call="async"` include and its content
  never reaches the client, so batching buys nothing and would give it a shared deadline it does not
  have. `combine` on an async include is ignored, and warned about once.
- **Duplicate URLs on one page are de-duplicated** within a batch: one part, both placeholders.
- **`fc-` is a reserved query-parameter prefix.** An include whose own query string uses one is never
  combined — it is fetched individually and logged once.
- **A long group splits.** More members than `max-batch-size`, or an encoded query longer than
  `max-url-length`, becomes several batches issued concurrently (§9).
- **One batch takes one thread-pool slot, one circuit-breaker sample and one time-limiter budget**
  where fifteen includes took fifteen of each. The flip side, stated plainly: one slow batch loses
  all of its members at once.
- **The batch is never cached.** It is sent with `x-frontcache-dynamic-request: true`, the existing
  signal that makes every Frontcache node on the path bypass its cache and forward — which matters in
  an `edge → filter → app` topology, since the combined URL carries whichever members happened to
  miss and caching that envelope would mean one useless entry per subset, at two nodes.

---

## 4. Wire contract — request

One GET to the fragment's own path:

```
GET {path}?fc-combine=1&fc-parts={n}&fc-part-0={q0}&fc-part-1={q1}&…&fc-part-{n-1}={qn-1}
```

| Parameter | Meaning |
| --- | --- |
| `fc-combine` | Protocol version, currently `1`. **Its presence is what makes this a combined request.** |
| `fc-parts` | Number of members, `n` — so the origin can validate and size its batch before parsing |
| `fc-part-{i}` | Member `i`'s **complete original query string**, without the leading `?`, percent-encoded as a single value. `i` runs `0 … n-1`; a member whose URL had no query sends an empty value |

Worked example — three cells, `id=2` already cached:

```
GET /fc/search-item.htm?fc-combine=1&fc-parts=2&fc-part-0=id%3D1%26lang%3Den&fc-part-1=id%3D3%26lang%3Den
Host: origin.example.com
x-frontcache-request-id: 5f0c…
x-frontcache-include-level: 1.c0
x-frontcache-client-ip: 203.0.113.7
```

Decoding `fc-part-0` gives `id=1&lang=en` — a normal query string, parsed with whatever your
framework already uses.

- **All members of a batch share the same path**; the path *is* the endpoint. The edge never combines
  across paths.
- **Indexed, not repeated** (`fc-part-0`, `fc-part-1`, …) so the origin never depends on its
  container preserving parameter order — the response is positional, so an ordering the origin cannot
  rely on would be the wrong thing to build on.
- **Carried as a value, never concatenated into the query grammar**, so no include URL can smuggle a
  parameter into the combined request or into a sibling member.
- Every other request header is exactly what a single include would have sent.

---

## 5. Wire contract — response

```
200 OK
Content-Type: application/json;charset=UTF-8
x-frontcache-combine: 1
```

```json
{
  "v": 1,
  "parts": [
    {
      "q": "id=1&lang=en",
      "status": 200,
      "headers": {
        "Content-Type": ["text/html;charset=UTF-8"],
        "x-frontcache-component-maxage": ["10d"],
        "x-frontcache-component-tags": ["coin|coin-1"],
        "x-frontcache-component-cache-level": ["L2"]
      },
      "body": "<div class=\"cell\">…</div>"
    },
    { "q": "id=3&lang=en", "status": 200, "headers": {}, "body": "…" }
  ]
}
```

1. **`x-frontcache-combine` on the response is the support signal** — the *only* way the edge knows
   the origin understood the request. An origin hand-writing this JSON and forgetting the header gets
   the individual-fetch fallback, however good the JSON is.
2. **`parts` is positional.** `parts.length` must equal `fc-parts`, and `parts[i]` must answer
   `fc-part-{i}`.
3. **`q` is an optional echo** of the member's decoded query string. When present the edge verifies
   it. It costs one field and catches the whole class of "the handler returned its results in a
   different order" bugs — which are otherwise invisible: the page renders, with every cell showing
   another cell's content.
4. **`status` is optional, default `200`.** A non-2xx part is not cached and that member falls back
   on its own; its siblings render and cache normally.
5. **`headers` is optional**, and is read exactly as a single include's response headers are:
   `x-frontcache-component-maxage`, `-tags`, `-refresh`, `-cache-level` and `Content-Type` mean what
   they always mean. **No `maxage` still means not cacheable** — the same default as everywhere else,
   not a special case.
6. **`body` is a JSON string**, UTF-8. Not base64: includes are text by construction.
7. **Unknown fields are ignored**, top-level and per-part, so the edge and the origin can be upgraded
   in either order.
8. The envelope is never scanned for `<fc:include>` and never enters the cache. Nested includes
   inside a *part's body* are found on the engine's next pass, after the splice, exactly as for a
   single include.

---

## 6. Writing the origin handler

### 6.1 Java

`frontcache-core` ships both halves of the contract so the two ends cannot drift —
`org.frontcache.include.combine.CombineRequest` and `CombineResponse`:

```java
List<Map<String, String[]>> parts = CombineRequest.parseParameters(request);
if (null == parts)
    return renderSingle(request);              // an ordinary single-fragment request

Map<Long, Coin> coins = dao.getByIds(idsOf(parts));   // ONE batched query - the entire point

CombineResponse out = new CombineResponse();
for (Map<String, String[]> part : parts)
    out.addComponent(CombineRequest.queryOf(part), render(coins, part), "10d", "coin-" + idOf(part));
out.writeTo(response);                          // sets the content type, the header and the body
```

`CombineResponse` also has `add(q, status, headers, body)` for full control of a part's headers and
`addError(q, status)` for a member the origin cannot answer.

### 6.2 Any other language

About thirty lines: decode *n* values, batch, emit JSON. Complete, in PHP — one file serving both the
single-fragment and the combined shape, which is what the contract expects of an endpoint:

```php
<?php
// /fc/search-item.php - a combine-aware fragment endpoint. PHP 7.4+.

const FC_COMBINE = 'fc-combine';   // presence = combined request; value = protocol version
const FC_PARTS   = 'fc-parts';     // member count
const FC_PART    = 'fc-part-';     // fc-part-0, fc-part-1, ... each one member's query string
const FC_MAX_AGE = '10d';

/**
 * The members' parameters, in order, or null when this is an ordinary single-fragment request.
 *
 * A protocol version this handler does not speak is deliberately answered as an ordinary request:
 * the edge then sees no x-frontcache-combine header and degrades correctly, rather than being
 * handed an envelope in a shape it cannot read.
 */
function fc_combine_parts(): ?array
{
    if (!isset($_GET[FC_COMBINE]) || (int) $_GET[FC_COMBINE] !== 1) {
        return null;
    }

    $parts = [];
    $count = (int) ($_GET[FC_PARTS] ?? 0);

    for ($i = 0; $i < $count; $i++) {
        // Already percent-decoded by PHP - the member's query travelled as one VALUE, so what is
        // in $_GET is the member's raw query string ("id=1&lang=en").
        parse_str($_GET[FC_PART . $i] ?? '', $params);
        $parts[] = $params;
    }

    return $parts;
}

function fc_part(string $body, string $query, string $tags): array
{
    return [
        'q'       => $query,          // optional, and verified by the edge when present
        'status'  => 200,
        'headers' => [
            'Content-Type'                  => ['text/html;charset=UTF-8'],
            'x-frontcache-component-maxage' => [FC_MAX_AGE],
            'x-frontcache-component-tags'   => [$tags],
        ],
        'body'    => $body,
    ];
}

$parts = fc_combine_parts();

if ($parts === null) {
    // Single fragment: cacheable on exactly the terms a combined part is.
    $id   = $_GET['id'] ?? null;
    $rows = load_items([$id]);

    header('Content-Type: text/html;charset=UTF-8');
    header('x-frontcache-component-maxage: ' . FC_MAX_AGE);
    header('x-frontcache-component-tags: item|item-' . $id);
    echo render_item($rows[$id] ?? null);
    exit;
}

// ONE batched load over every member's key - the entire point of the feature. A loop calling
// load_items() once per member would pay exactly what the unbatched includes paid.
$ids  = array_map(static fn(array $q) => $q['id'] ?? null, $parts);
$rows = load_items($ids);

$envelope = ['v' => 1, 'parts' => []];

foreach ($parts as $i => $q) {
    $id    = $q['id'] ?? null;
    $query = $_GET[FC_PART . $i];

    if (!isset($rows[$id])) {
        // Not found: this member is not cached and falls back on its own. Its siblings are fine.
        $envelope['parts'][] = ['q' => $query, 'status' => 404];
        continue;
    }

    $envelope['parts'][] = fc_part(render_item($rows[$id]), $query, 'item|item-' . $id);
}

// Before any output. The second header is the whole handshake - without it the edge concludes
// this origin does not implement combining, however good the JSON is.
header('Content-Type: application/json;charset=UTF-8');
header('x-frontcache-combine: 1');

echo json_encode($envelope, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
```

Three PHP-specific notes, each of which is silent when got wrong:

- **`$_GET['fc-part-0']` works.** PHP rewrites `.`, space and `[` in parameter *names* to `_`, but
  leaves `-` alone — which is why the contract's parameter names are hyphenated. A dotted spelling
  would have arrived as `fc_part_0` and been unreachable under the name the edge sent.
- **`parse_str()` applies that same rewriting to the member's own parameter names.** If your fragment
  takes a parameter with a `.` or a space in its name, parse it yourself (`explode('&', …)` +
  `urldecode`) rather than with `parse_str`.
- **`JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE` is cosmetic.** The edge parses either form;
  the flags just keep the envelope smaller and readable in a `curl`.

---

## 7. When something goes wrong

| | |
| --- | --- |
| **Origin has no combine handler** | Detected by the missing response header. Members are re-fetched individually and the page renders. The group is then remembered as unsupported for a minute (`unsupported-ttl`), so an origin that never gains a handler costs one probe per group per minute rather than one per page view — and a rolling deploy converges within that minute. |
| **Envelope malformed, part count wrong, or a `q` echo out of order** | Rejected whole. Every member of that batch falls back through `fallbacks.conf`, keyed on **its own** URL, so fallback entries written for single includes keep working. Logged at ERROR and counted. |
| **Batch times out, circuit opens, origin 5xx** | One failed origin command, so members fall back individually — the same path a timed-out single include takes. |
| **One part carries a non-2xx status** | That member falls back. Its siblings render and cache normally. |

Nothing here fails the page. The failure mode to actually watch for is the *silent* one: an origin
that says it implements the contract and does not — which is what
`frontcache_include_combine_errors_total` exists for (§8).

---

## 8. Observability

**Trace headers.** With `front-cache.log-to-headers=true`, a combined member's line reads
`include-combined` and carries the **batch's** elapsed time — so *N* members report *N* identical
durations that do not sum to the page time. That is what the marker is there to explain.

**Metrics.** Three counter families, with `front-cache.metrics.export` on
([console-dashboards.md](console-dashboards.md)):

```
frontcache_include_combine_batches_total{result="ok|unsupported|error|failed"}
frontcache_include_combine_members_total{result="cached|combined|individual|fallback"}
frontcache_include_combine_errors_total{kind="parse|count|echo"}
```

The first two give the number the feature exists to raise — average fragments served per origin
request, which nothing else in the process can produce:

```promql
rate(frontcache_include_combine_members_total{result="combined"}[5m])
  / rate(frontcache_include_combine_batches_total{result="ok"}[5m])
```

**`frontcache_include_combine_errors_total` is the one to alert on.** Every value of its `kind` is an
origin that claims the contract and breaks it, and there is no other signal: the page still renders,
out of fallbacks, and every other meter reads healthy. `batches_total{result="unsupported"}` is the
softer version — markup switched over, handler not deployed.

The four member buckets are exhaustive, so `cached + combined + individual + fallback` is the number
of combinable includes the node has seen. No series is labelled by URL.

---

## 9. Configuration

In `FRONTCACHE_HOME/conf/frontcache.properties`. All optional; the defaults are what every install
channel ships.

| Key | Default | What it does |
| --- | --- | --- |
| `front-cache.include-processor.impl.concurrent.combine` | `true` | Kill switch. Combining only acts on includes that ask for it, so `true` changes nothing for markup that does not |
| `…concurrent.combine.max-batch-size` | `30` | Members per origin request; a longer group splits into several, issued concurrently |
| `…concurrent.combine.max-url-length` | `2000` | Second cap, on the encoded query — a batch past a proxy's request-line limit comes back as a `414` that looks like nothing else |
| `…concurrent.combine.unsupported-ttl` | `60000` | How long "this origin has no combine handler" is remembered, per endpoint, in ms |

Combining requires the default `ConcurrentIncludeProcessor`
([concept.md §3](concept.md#3-inside-the-engine)).

---

## 10. What it does not do

- **It does not reduce edge work.** Still *N* placeholders, *N* cache lookups, *N* log lines.
- **It does not change cache size, cache keys, TTLs or invalidation.** Each part is stored under the
  key it would have had on its own.
- **It cannot give a fragment something only the parent request had.** A cell that renders a value
  taken from the parent page's own query result still cannot see it, batched or not — pass it as a
  parameter (it then joins the cache key) or have the handler fetch it.
- **It is not a reason to fragment a page that does not need fragmenting.** The cheaper lever is
  still an `<fc:component>` on the page itself; combining is what makes fragments viable *when the
  fragments are wanted for their own sake* — independent TTLs, or reuse of one fragment across many
  pages.

---

## 11. Verify it

With the markup in place and the handler deployed, call the endpoint the way the edge would:

```sh
curl -si "http://origin:8080/fc/search-item.htm?fc-combine=1&fc-parts=2\
&fc-part-0=id%3D1%26lang%3Den&fc-part-1=id%3D3%26lang%3Den" | head -20
```

Three things to check, in this order:

1. `x-frontcache-combine: 1` is in the response headers — without it nothing else matters.
2. `parts` has exactly two entries, in the order asked.
3. Each part carries `x-frontcache-component-maxage`, or it will never be cached.

Then load a page of fragments with a cold cache and watch your origin's own query log: the count is
the whole point, and it is the number that should not move when you add fragments.

---

Concepts: [concept.md](concept.md) ·
JSP tags: [jsp-tags.md](jsp-tags.md) ·
Headers: [http-headers.md](http-headers.md) ·
Licensing: <https://www.eternita.co/frontcache.html>
