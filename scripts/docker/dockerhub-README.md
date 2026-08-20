<!--
The description shown on Docker Hub for pavlikovskiy/frontcache-server. Docker Hub has no API
in our release flow, so this is pasted into the repository description by hand - step 9 of
docs/frontcache-release-steps.md, in the same push as the images.

Keep it short. It is the page someone reads when they are deciding whether to `docker run`.
-->

# Frontcache

Page-fragment cache and reverse proxy. Put it in front of a web app in any language: it caches
page fragments, stitches together `<fc:include url="..."/>` markers (concurrently), and adds
circuit breaking and fallbacks.

- Docs and examples: https://github.com/eternita/frontcache
- Install guide: https://github.com/eternita/frontcache/blob/master/docs/HOWTO-install.md

## Run it

```sh
docker run -d --name frontcache --restart unless-stopped \
  -p 9080:9080 \
  -e ORIGIN_HOST=your-origin.example.com \
  -v /srv/frontcache/conf:/opt/frontcache-server/FRONTCACHE_HOME/conf \
  pavlikovskiy/frontcache-server:2.6.0
```

Frontcache serves **plain HTTP on 9080 and terminates no TLS.** Whatever fronts it owns 80/443 —
an ingress, an ALB, Cloudflare, or nginx. A worked nginx front door (a two-container compose
stack, or a script for a VM) is at
https://github.com/eternita/frontcache/tree/master/examples/front-door.

## Tags

| Tag | |
| --- | --- |
| `2.6.0`, `2.6` | the release, and its patch-tracking alias |
| `2.6.0-slim`, `2.6-slim` | **the same image**, kept so deployments pinned to `-slim` keep working |
| `latest` | still 2.5.x — see below |

Multi-arch (`linux/amd64`, `linux/arm64`). Pin an exact version.

> **Changed in 2.6.0: this image no longer contains nginx.** Through 2.5.1, `frontcache-server:<v>`
> was nginx + Frontcache on 80/443 and `-slim` was Frontcache alone. There is now one image and it
> is what `-slim` was, so **`-p 80:80` no longer works** — publish 9080 instead, and put your own
> front door in front. Deployments pinned to `-slim`, or to any older tag, are unaffected; no tag
> was deleted or rewritten. `latest` stays on 2.5.x until the cutoff announced with 2.7.0.
> Details: https://github.com/eternita/frontcache/blob/master/docs/release-notes-2.6.0.md

## Configuration

| Env | |
| --- | --- |
| `ORIGIN_HOST` | your backend. Set it and it is written into `frontcache.properties` on every start; leave it unset and your mounted file is the source of truth and is never rewritten |
| `FRONTCACHE_HTTP_PORT` | the port inside the container (default 9080) |

Config is upgrade-safe: the image keeps its defaults outside `conf/` and seeds only the files
that are *missing*, so a new image can add config to an existing volume without touching what
you edited.

Set `front-cache.http-port` / `front-cache.https-port` in your config to the ports **clients**
see (80/443 behind a front door). They drive redirect rewriting.

A `HEALTHCHECK` GETs `/frontcache-io?action=get-cache-state` locally; `200` means Jetty, the
webapp and the engine are up.

## Console

`pavlikovskiy/frontcache-console` — realtime stats and cache management on 7080. It has **no
authentication** and can invalidate cache across a fleet; keep it on loopback.

## Licence

https://www.eternita.co/frontcache-license.html
