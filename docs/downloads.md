# Frontcache — Downloads

Direct links to every published artifact for the two current releases, **2.9.0** (current) and
**2.8.0** (previous). Everything here is **public read** — no account, no token, no login. This
page is the link list; [install-guide.md](install-guide.md) is how you actually install each of
them, and [release-notes/](release-notes/) is what changed between them — read the note for the
version you are moving to before you move to it.

Everything Java lives under `https://repo.eternita.co/maven2/org/frontcache/`, the images live on
Docker Hub, and **every file below has a companion checksum** — append `.sha256` to any link on
this page.

Both releases are **Java 25** bytecode and **Jakarta EE 10** (`jakarta.servlet`, Servlet 6.0).
They will not load on an older JVM or in a `javax.servlet` container — except that the container
images and the bundled-runtime archives carry their own runtime, so those need no JDK at all.

| Which do I want | Channel | How to install it |
| --- | --- | --- |
| Cache a Java app from inside its own JVM (use case #1) | [library](#library--frontcache-core-and-frontcache-agent) | [install-guide §A](install-guide.md#a-library--frontcache-inside-your-java-app) |
| A reverse proxy in front of an app in any language (use cases #2, #3) | [server archive](#standalone-server) | [install-guide §B](install-guide.md#b-archive--unpack-and-run) |
| The same, installed as a systemd service | [installer](#standalone-server) | [install-guide §C](install-guide.md#c-installer--a-systemd-service-on-a-vm) |
| Containers, Kubernetes, quickest trial, **or Windows** | [image](#container-images) | [install-guide §D](install-guide.md#d-container-image) · [docker.md](docker.md) |
| Realtime stats and cache invalidation | [console](#console) | [install-guide §E](install-guide.md#e-console--realtime-stats-and-cache-management) |

---

## Standalone server

| Artifact | 2.9.0 | 2.8.0 | Size |
| --- | --- | --- | --- |
| Bundle — needs a JDK 25 on the host | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.9.0/frontcache-server-2.9.0.tar.gz) · [.zip](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.9.0/frontcache-server-2.9.0.zip) | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.8.0/frontcache-server-2.8.0.tar.gz) · [.zip](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.8.0/frontcache-server-2.8.0.zip) | ~37 MB |
| Bundled runtime — Linux x86-64 | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.9.0/frontcache-server-2.9.0-linux-x64.tar.gz) | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.8.0/frontcache-server-2.8.0-linux-x64.tar.gz) | ~92 MB |
| Bundled runtime — Linux arm64 | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.9.0/frontcache-server-2.9.0-linux-aarch64.tar.gz) | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.8.0/frontcache-server-2.8.0-linux-aarch64.tar.gz) | ~91 MB |
| Bundled runtime — macOS arm64 | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.9.0/frontcache-server-2.9.0-macos-aarch64.tar.gz) | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.8.0/frontcache-server-2.8.0-macos-aarch64.tar.gz) | ~86 MB |
| Installer script — systemd service, JDK and service user included | [-installer.sh](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.9.0/frontcache-server-2.9.0-installer.sh) | [-installer.sh](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.8.0/frontcache-server-2.8.0-installer.sh) | 27 KB |
| Compose file | [-compose.yml](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.9.0/frontcache-server-2.9.0-compose.yml) | [-compose.yml](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.8.0/frontcache-server-2.8.0-compose.yml) | 3 KB |
| Compose env template | [-env.example](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.9.0/frontcache-server-2.9.0-env.example) | [-env.example](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/2.8.0/frontcache-server-2.8.0-env.example) | 2 KB |

A bundled-runtime build unpacks to the **same** directory name as the plain bundle with one extra
`runtime/` directory, and the launcher prefers that runtime over any `JAVA_HOME` on the host — so
every step after unpacking is identical. There is **no Windows build**; use the container there.

The installer downloads and checksums the archive for you: `--with-runtime` picks the
bundled-runtime build for the host's platform, `--with-console` adds the console as a second
service, `--archive PATH` installs from a file you staged yourself (air-gapped hosts). Flags in
full in [install-guide §C](install-guide.md#c-installer--a-systemd-service-on-a-vm).

The server serves **plain HTTP on 9080** and terminates no TLS — a front door on 80/443 is a
separate, deliberate step: [examples/front-door](../examples/front-door).

## Console

| Artifact | 2.9.0 | 2.8.0 | Size |
| --- | --- | --- | --- |
| Bundle — needs a JDK 25 on the host | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-console/2.9.0/frontcache-console-2.9.0.tar.gz) · [.zip](https://repo.eternita.co/maven2/org/frontcache/frontcache-console/2.9.0/frontcache-console-2.9.0.zip) | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-console/2.8.0/frontcache-console-2.8.0.tar.gz) · [.zip](https://repo.eternita.co/maven2/org/frontcache/frontcache-console/2.8.0/frontcache-console-2.8.0.zip) | ~48 MB |
| Bundled runtime — Linux x86-64 | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-console/2.9.0/frontcache-console-2.9.0-linux-x64.tar.gz) | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-console/2.8.0/frontcache-console-2.8.0-linux-x64.tar.gz) | ~103 MB |
| Bundled runtime — Linux arm64 | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-console/2.9.0/frontcache-console-2.9.0-linux-aarch64.tar.gz) | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-console/2.8.0/frontcache-console-2.8.0-linux-aarch64.tar.gz) | ~102 MB |
| Bundled runtime — macOS arm64 | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-console/2.9.0/frontcache-console-2.9.0-macos-aarch64.tar.gz) | [.tar.gz](https://repo.eternita.co/maven2/org/frontcache/frontcache-console/2.8.0/frontcache-console-2.8.0-macos-aarch64.tar.gz) | ~97 MB |

The console is a separate process from the server, on **7080**, and it **has no authentication of
its own** while being able to invalidate cache across your whole fleet. Keep it on loopback or an
internal network. See [security.md](security.md) and
[console-dashboards.md](console-dashboards.md).

## Library — frontcache-core and frontcache-agent

Add the repository and let your build tool fetch these; the direct links are here for offline
mirroring and for checking a checksum by hand.

```groovy
repositories {
    mavenCentral()
    maven { url = 'https://repo.eternita.co/maven2' }
}
dependencies {
    implementation 'org.frontcache:frontcache-core:2.9.0'
    implementation 'org.frontcache:frontcache-agent:2.9.0'   // optional: invalidate from app code
}
```

| Artifact | Coordinate | 2.9.0 | 2.8.0 |
| --- | --- | --- | --- |
| The filter and engine | `org.frontcache:frontcache-core` | [jar](https://repo.eternita.co/maven2/org/frontcache/frontcache-core/2.9.0/frontcache-core-2.9.0.jar) · [pom](https://repo.eternita.co/maven2/org/frontcache/frontcache-core/2.9.0/frontcache-core-2.9.0.pom) · [javadoc](https://repo.eternita.co/maven2/org/frontcache/frontcache-core/2.9.0/frontcache-core-2.9.0-javadoc.jar) | [jar](https://repo.eternita.co/maven2/org/frontcache/frontcache-core/2.8.0/frontcache-core-2.8.0.jar) · [pom](https://repo.eternita.co/maven2/org/frontcache/frontcache-core/2.8.0/frontcache-core-2.8.0.pom) · [javadoc](https://repo.eternita.co/maven2/org/frontcache/frontcache-core/2.8.0/frontcache-core-2.8.0-javadoc.jar) |
| Invalidation client for Java callers | `org.frontcache:frontcache-agent` | [jar](https://repo.eternita.co/maven2/org/frontcache/frontcache-agent/2.9.0/frontcache-agent-2.9.0.jar) · [pom](https://repo.eternita.co/maven2/org/frontcache/frontcache-agent/2.9.0/frontcache-agent-2.9.0.pom) | [jar](https://repo.eternita.co/maven2/org/frontcache/frontcache-agent/2.8.0/frontcache-agent-2.8.0.jar) · [pom](https://repo.eternita.co/maven2/org/frontcache/frontcache-agent/2.8.0/frontcache-agent-2.8.0.pom) |
| `FRONTCACHE_HOME` skeleton for filter mode | — | [-home.zip](https://repo.eternita.co/maven2/org/frontcache/frontcache-core/2.9.0/frontcache-core-2.9.0-home.zip) | [-home.zip](https://repo.eternita.co/maven2/org/frontcache/frontcache-core/2.8.0/frontcache-core-2.8.0-home.zip) |

The `-home.zip` is not optional reading material: Frontcache takes its configuration from a
`FRONTCACHE_HOME` directory, not from your app's config, and that zip is the filter-mode skeleton
(`conf/frontcache.properties` plus a `README-FILTER.md`). Two `CHANGE_ME` values in it must be
edited — `front-cache.default-domain` and `front-cache.api-key`.

Every version ever published is listed in the repository's own metadata, if you need an older one:
[frontcache-core](https://repo.eternita.co/maven2/org/frontcache/frontcache-core/maven-metadata.xml) ·
[frontcache-agent](https://repo.eternita.co/maven2/org/frontcache/frontcache-agent/maven-metadata.xml) ·
[frontcache-server](https://repo.eternita.co/maven2/org/frontcache/frontcache-server/maven-metadata.xml) ·
[frontcache-console](https://repo.eternita.co/maven2/org/frontcache/frontcache-console/maven-metadata.xml).
Directory browsing is off, so fetch a file by its exact name.

## Container images

Multi-arch (amd64 + arm64), each with a `HEALTHCHECK`.

| Image | Pull | Tags |
| --- | --- | --- |
| Server — plain HTTP on **9080**, no TLS | `pavlikovskiy/frontcache-server:2.9.0` · `:2.8.0` | [Docker Hub](https://hub.docker.com/r/pavlikovskiy/frontcache-server/tags) |
| Console — **7080** | `pavlikovskiy/frontcache-console:2.9.0` · `:2.8.0` | [Docker Hub](https://hub.docker.com/r/pavlikovskiy/frontcache-console/tags) |

```sh
docker run -d --name frontcache -p 9080:9080 \
  -e ORIGIN_HOST=origin.example.com \
  pavlikovskiy/frontcache-server:2.9.0
```

**Pin the exact version.** `2.9`, `2.8` and `latest` all exist and `latest` currently points at
2.9.0; naming a floating tag in production is how you get surprised.

**The `-slim` distinction is gone.** Through 2.5.1 the plain `frontcache-server` tag was
nginx + Frontcache on 80/443 and `-slim` was Frontcache alone. From 2.6.0 there is one image and
it is what `-slim` was: it publishes **9080** and terminates no TLS. The old `2.6.0-slim` tag is
still on Docker Hub for that transition and there is no 2.7, 2.8 or 2.9 equivalent.

## Checksums

Every artifact above has a `.sha256` beside it — the same URL with `.sha256` appended.

```sh
V=2.9.0
BASE=https://repo.eternita.co/maven2/org/frontcache/frontcache-server/$V

curl -fLO $BASE/frontcache-server-$V.tar.gz
curl -fLO $BASE/frontcache-server-$V.tar.gz.sha256
# the published checksum may be a bare hash with no filename, so compare the hash field
# rather than using `shasum -c`, which needs the `hash  filename` form:
[ "$(shasum -a 256 frontcache-server-$V.tar.gz | cut -d' ' -f1)" \
  = "$(cut -d' ' -f1 < frontcache-server-$V.tar.gz.sha256)" ] && echo "checksum OK"
```

For the installer this step is the whole point — download it, check it, **then** run it as root.
Never `curl | sudo bash`.

## Which version

**2.9.0** is current and is what a new install should take. **2.8.0** is the previous release and
is still published for a rollback or a pinned fleet.

Upgrading between the two is the ordinary path — unpack beside the old version, copy your `conf/`
across, flip the symlink, restart; `cache/` is pure cache and can be discarded freely. Full steps,
including what the installer and the container do for you, in
[install-guide.md](install-guide.md#upgrade).

Coming from **2.6.x** instead, two carried-over files need attention — `hystrix.properties` was
renamed to `resilience.properties` (2.7 moved the circuit breakers to Resilience4j), and
`fc-logback.xml` needs a hand edit or `logs/fallback.log` stays silently empty. Both are spelled
out in [install-guide.md](install-guide.md#upgrade), and what changed underneath is in
[resilience-command-flow.md](resilience-command-flow.md).

---

Install: [install-guide.md](install-guide.md) ·
Containers: [docker.md](docker.md) ·
Concepts: [concept.md](concept.md) ·
Topologies: [deployment-usecases.md](deployment-usecases.md) ·
Licensing: <https://www.eternita.co/frontcache.html>
