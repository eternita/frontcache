# syntax=docker/dockerfile:1
#
# The Frontcache server image, built FROM the published distribution archive.
#
# Frontcache alone: plain HTTP on 9080, no TLS and no front door. Put it behind whatever
# terminates TLS for you - an ingress, an ALB, Cloudflare, or nginx. A worked nginx example
# (two containers, or a VM script) lives in the public repo under examples/front-door.
#
# The build context is a small STAGING DIRECTORY assembled by
# scripts/docker/build-push-images.sh: this Dockerfile, the entrypoint, and the distribution
# tarball renamed to frontcache-server.tar.gz. Building from the release archive rather than
# from the repo working tree means the image is reproducible from a published artifact, and
# the build host needs no JDK and no Gradle run.
#
# Build it with the script, not by hand:
#   ./scripts/docker/build-push-images.sh 2.5.0
ARG FRONTCACHE_VERSION=2.5.0

FROM eclipse-temurin:25-jdk
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

COPY seed-conf.sh entrypoint.sh /opt/fc-server/
RUN chmod +x /opt/fc-server/entrypoint.sh \
             /opt/frontcache-server/server/bin/frontcache /opt/frontcache-server/bin/frontcache

ENV FRONTCACHE_HTTP_PORT=9080
EXPOSE 9080

# Liveness, not authorization: the IO servlet answers 200 with an "access denied" JSON body
# when the request carries no front-cache.api-key, so a plain local GET proves Jetty + the
# webapp + the engine are up without needing an API key.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS -o /dev/null "http://127.0.0.1:${FRONTCACHE_HTTP_PORT}/frontcache-io?action=get-cache-state"

ENTRYPOINT ["/opt/fc-server/entrypoint.sh"]
