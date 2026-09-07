# Frontcache Docker

Container images for Frontcache.

- **`fc-server/`** — the **Frontcache server** image and the compose project that runs it.
  Frontcache alone (embedded Jetty 12), plain HTTP on 9080, no TLS. Whatever terminates TLS
  goes in front of it: a Kubernetes ingress, an ALB, Cloudflare, or nginx.

  > Through 2.5.1 this image came in two variants — the default was nginx + Frontcache on
  > 80/443, and `-slim` was Frontcache alone. **There is now one image, and it is what `-slim`
  > was.** The nginx front door lives in the public repo's
  > [`examples/front-door`](https://github.com/eternita/frontcache/tree/master/examples/front-door),
  > which has both a two-container compose stack and a script for a VM. See
  > [the plan](../../docs/archive/move-out-nginx-plan.md). 2.6.0 was published under the `-slim` names as
  > well, as a compatibility alias; from 2.7.0 only the plain tag is pushed. Nothing was deleted,
  > so every existing `-slim` tag still resolves.
- **`fc-console/`** — the **Frontcache console** image (realtime stats and cache management,
  port 7080). It is a separate container because it is a separate process: the server's
  launcher deploys exactly one webapp at context `/`.

> **Log analytics moved out.** The ELK stack (Elasticsearch + Kibana + Logstash) that indexed
> the request, error, fallback and rejected-request logs, and the `pull-logs` /
> `start-fc-elk` / `stop-fc-elk` scripts that drove it, now live in the public repo's
> [`examples/log-analytics`](https://github.com/eternita/frontcache/tree/master/examples/log-analytics).
> Same stack, same dashboards, same script names — the `fc-elk/` subdirectory is flattened into
> the example root, so run them from there. The compose project name is pinned to `fc-elk`, so
> an existing local stack keeps its `fc-elk_es-data` / `fc-elk_ls-data` volumes.

The images are built **from the distribution archives** (`./gradlew dist`), not from the
working tree, so an image is reproducible from a published artifact — see
[the distribution proposal](../../docs/archive/frontcache-distribution-proposal.md).

## Requirements

- Docker with the Compose plugin (`docker compose`) and `buildx`
- To **build** images: a JDK to run the Gradle `dist` task on the host (the images ship JDK 25).
  To only **run** published images, Docker is all you need.

---

## Running the server (and console)

```sh
cd fc-server
cp .env.example .env         # set ORIGIN_HOST / ORIGIN_PATHS / ports
cd ..
./start-fc-server.sh                  # build :local images, start the server
./start-fc-server.sh --with-console   # ... and the console on 127.0.0.1:7080
```

`start-fc-server.sh` builds the archives and the `:local` images, then brings up the compose
project. Then:

- `http://localhost:9080` — plain HTTP. There is no TLS here and nothing on 80/443; that is
  the front door's job, and the front door is not in this repo.
- `docker inspect --format '{{.State.Health.Status}}' fc-server`
- `docker logs -f fc-server`
- `./stop-fc-server.sh` (add `-v` to drop the conf/cache/log volumes)

To run a **published** image instead of building, set `FC_VERSION` to a release in `.env` and
`docker compose pull && docker compose up -d`.

### Configuration

`fc-server/.env` drives the compose project; `.env.example` documents every key. The ones
that matter most:

| Key | Meaning |
| --- | --- |
| `FC_VERSION` | the image tag to run (`local` for images you built; otherwise an exact release) |
| `ORIGIN_HOST` | the backend. **Leave it empty** to let the mounted `frontcache.properties` own `front-cache.origin-host` — the entrypoint then reads the value and never rewrites the file. Set it, and it is authoritative and gets written into the file on every start. |
| `FC_HTTP_PORT` / `FC_BIND` | the published port and bind address. Keep it on `127.0.0.1` when the front door is on the same host |
| `FC_CONF_DIR` | where the config lives. Default is the `fc-conf` named volume; point it at a host directory (`./conf`) to own the config on the host — recommended for production |
| `FC_NODES` / `FC_API_KEY` | the nodes the console monitors, and the shared api-key |
| `FC_CONSOLE_BIND` | console bind address, **loopback by default** |

**Config is upgrade-safe.** The image keeps a read-only copy of the shipped config outside
`FRONTCACHE_HOME/conf`, and the entrypoint copies over only the files that are *missing*.
That matters because Docker seeds a named volume from the image **only the first time it is
used** — without this, a newer image's config files would never reach an existing volume, and
upgrades would silently keep running on old config. Files you have edited are never touched.

> **The console the image renders has no login**, and can invalidate cache across the whole fleet.
> The console itself authenticates - a login page, one user - but the config the entrypoint writes
> from `FC_NODES` carries no `user`/`password`, so authentication is off until you mount a
> `frontcache-console.conf` that sets them. It is published on `127.0.0.1` by default; reach it over
> an ssh tunnel, or put an authenticating proxy in front of it. Do not bind it to a public
> interface.
>
> Note that this image's `HEALTHCHECK` (`curl -f .../realtime`) predates console authentication and
> **reports unhealthy on a console that has credentials**, since an un-authenticated request is a
> `401` by design. It only passes today because the rendered config sets no login.

## Building and publishing images

```sh
./build-push-images.sh                          # version.properties, host arch, loaded locally
./build-push-images.sh 2.5.0 --push             # multi-arch (amd64 + arm64), push 2.5.0 and 2.5
./build-push-images.sh 2.5.0 --push --latest    # ... and :latest
./build-push-images.sh local --only server      # just the two server images
./build-push-images.sh 2.5.0 --push --dry-run   # print the buildx commands and stop
```

Two images are produced:

| Image | Contents | Ports |
| --- | --- | --- |
| `pavlikovskiy/frontcache-server:<tag>` | Frontcache | 9080 |
| `pavlikovskiy/frontcache-console:<tag>` | console | 7080 |

A semver tag also publishes its `X.Y` alias, so `2.5.0` pushes `2.5` as well and a customer
can track patch releases without tracking `:latest`. Customer-facing docs always name the
exact tag.

`--push` implies a **multi-platform** build, which cannot be loaded into the local image
store, so it goes straight to the registry (`docker login` first). It runs on a dedicated
`frontcache` buildx builder, created on demand. Without `--push` the build is
single-platform (the host's) and is loaded locally.

Override the namespace with `NS=` (or a whole name with `IMAGE_SERVER=` / `IMAGE_CONSOLE=`).
The planned move from `pavlikovskiy/*` to `eternita/*` is a
[follow-up](../../docs/archive/frontcache-distribution-followup.md) — a dual-push during the overlap
is just two runs with different `NS`.

### Running a published image without compose

```sh
docker run -d --name fc-server --restart unless-stopped \
  -p 9080:9080 \
  -e ORIGIN_HOST=origin.example.com \
  -v /srv/frontcache/conf:/opt/frontcache-server/FRONTCACHE_HOME/conf \
  -v fc-cache:/opt/frontcache-server/FRONTCACHE_HOME/cache \
  pavlikovskiy/frontcache-server:2.5.0
```

The image carries a `HEALTHCHECK` that GETs `/frontcache-io?action=get-cache-state` locally.
That endpoint answers `200` with an "access denied" JSON body when the request carries no
`front-cache.api-key`, which makes it a liveness probe that needs no API key: a `200` proves
Jetty, the webapp and the engine are up.

Remember `front-cache.http-port` / `front-cache.https-port` in the mounted config: they are
what redirect rewriting uses, so they must be the ports **clients** see (80/443 behind a front
door), not 9080.

---

## Scripts

Each script ships in two flavours: a Bash version (`*.sh`, macOS/Linux) and a
PowerShell version (`*.ps1`, Windows — PowerShell 6+/`pwsh`). They are behaviourally
equivalent; the table lists the Bash names.

| Script | Purpose |
| --- | --- |
| `start-fc-server.sh` | Build the `:local` images and start the stack (`--with-console`, `--no-build`) |
| `stop-fc-server.sh` | Stop/remove the stack (`-v` drops volumes) |
| `build-push-images.sh "<tag>"` | Build both images from the release archives; `--push` to publish |

### Windows (PowerShell)

Run the `.ps1` equivalents from a PowerShell prompt. Env-var overrides use
`$env:NAME="value"` and `--latest` becomes the `-Latest` switch:

```powershell
cd scripts\docker
.\start-fc-server.ps1                       # gradle dist -> image build -> up -d
.\start-fc-server.ps1 -WithConsole          # ... and the console
.\stop-fc-server.ps1 -v                     # stop + drop volumes
.\build-push-images.ps1 2.5.0 -Push -Latest # build both images, push 2.5.0 / 2.5 / latest
```

Windows is also the answer to "how do I run the server bundle on Windows": the launcher
scripts in the zip are bash-only, so **use the container on Windows** rather than the
archive.

Requirements on Windows: Docker Desktop (with `docker compose` and `buildx`) and a JDK on
PATH for the Gradle `dist` task (`gradlew.bat`). If scripts are blocked by execution policy,
launch them with `powershell -ExecutionPolicy Bypass -File .\start-fc-server.ps1`.
