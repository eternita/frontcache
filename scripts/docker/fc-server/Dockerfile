# syntax=docker/dockerfile:1
#
# Frontcache server images, built FROM the published distribution archive.
#
# Two targets out of one Dockerfile, so both are guaranteed to carry the identical
# Frontcache bundle and JDK:
#
#   --target slim  ->  Frontcache alone, plain HTTP on 9080, no nginx.
#                      For Kubernetes / an existing ingress / ALB - the use case #3 edges.
#   --target full  ->  nginx (80/443, terminates TLS) + Frontcache. The containerized
#                      equivalent of the single-host VM install. This is the default.
#
# The build context is a small STAGING DIRECTORY assembled by
# scripts/docker/build-push-images.sh: this Dockerfile, the entrypoints, and the
# distribution tarball renamed to frontcache-server.tar.gz. Building from the release
# archive rather than from the repo working tree means the image is reproducible from a
# published artifact, and the build host needs no JDK and no Gradle run.
#
# Build it with the script, not by hand:
#   ./scripts/docker/build-push-images.sh 2.5.0
ARG FRONTCACHE_VERSION=2.5.0

# ---- base: the unpacked bundle on a JDK 25 -----------------------------------
FROM eclipse-temurin:25-jdk AS base
ARG FRONTCACHE_VERSION

# curl backs the HEALTHCHECK below and makes the container debuggable from inside.
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# ADD auto-extracts a local .tar.gz, so the image needs no unzip/tar step of its own.
# The archive expands to frontcache-server-<version>/; the symlink keeps every path below
# version-independent, the same shape the VM installer uses.
ADD frontcache-server.tar.gz /opt/
RUN test -d "/opt/frontcache-server-${FRONTCACHE_VERSION}" \
      || { echo "ERROR: archive did not expand to /opt/frontcache-server-${FRONTCACHE_VERSION} - wrong --build-arg FRONTCACHE_VERSION?" >&2; exit 1; } \
 && ln -s "/opt/frontcache-server-${FRONTCACHE_VERSION}" /opt/frontcache-server

# Keep a read-only copy of the shipped config OUTSIDE FRONTCACHE_HOME/conf, so a volume or
# bind mount over conf/ cannot hide it. The entrypoint seeds only the files that are
# missing, which is what lets an image upgrade deliver NEW config files into an existing
# conf volume - Docker itself seeds a named volume from the image only on first use.
RUN mkdir -p /opt/frontcache-defaults \
 && cp -a /opt/frontcache-server/FRONTCACHE_HOME/conf /opt/frontcache-defaults/conf

COPY seed-conf.sh /opt/fc-server/seed-conf.sh
RUN chmod +x /opt/frontcache-server/server/bin/frontcache /opt/frontcache-server/bin/frontcache

# ---- slim: Frontcache only ---------------------------------------------------
FROM base AS slim

COPY entrypoint-slim.sh /opt/fc-server/entrypoint-slim.sh
RUN chmod +x /opt/fc-server/entrypoint-slim.sh

ENV FRONTCACHE_HTTP_PORT=9080
EXPOSE 9080

# Liveness, not authorization: the IO servlet answers 200 with an "access denied" JSON body
# when the request does not arrive on front-cache.management.port, so a plain local GET
# proves Jetty + the webapp + the engine are up without needing a site key.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS -o /dev/null "http://127.0.0.1:${FRONTCACHE_HTTP_PORT}/frontcache-io?action=get-cache-state"

ENTRYPOINT ["/opt/fc-server/entrypoint-slim.sh"]

# ---- full: nginx front door + Frontcache (default target) --------------------
FROM slim AS full

# nginx for the public front door; openssl for keystore -> PEM extraction at startup.
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends nginx openssl \
 && rm -rf /var/lib/apt/lists/*

# render-nginx-site.sh is the shared nginx template, also used by the VM installer; the
# build script stages it into the context from scripts/dist/.
COPY entrypoint.sh render-nginx-site.sh /opt/fc-server/
RUN chmod +x /opt/fc-server/entrypoint.sh /opt/fc-server/render-nginx-site.sh

# 80/443 public (nginx). 9080 stays internal (Frontcache HTTP).
EXPOSE 80 443

# Probe THROUGH nginx, so one check covers both processes.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS -o /dev/null "http://127.0.0.1/frontcache-io?action=get-cache-state"

ENTRYPOINT ["/opt/fc-server/entrypoint.sh"]
