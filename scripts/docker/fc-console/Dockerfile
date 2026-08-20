# syntax=docker/dockerfile:1
#
# Frontcache console image, built FROM the published distribution archive.
#
# The console is its own container because it is its own process: the server's
# FrontcacheJettyServer launcher deploys exactly one webapp at context "/", so the console
# cannot be hosted alongside it. It listens on 7080 and talks to each Frontcache node over
# that node's management API.
#
# Build context is a staging directory assembled by
# scripts/docker/build-push-images.sh (this Dockerfile, the entrypoint, and the
# distribution tarball renamed to frontcache-console.tar.gz).
#
#   ./scripts/docker/build-push-images.sh 2.5.0 --only console
ARG FRONTCACHE_VERSION=2.5.0

FROM eclipse-temurin:25-jdk
ARG FRONTCACHE_VERSION

# curl backs the HEALTHCHECK and makes the container debuggable from inside.
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# ADD auto-extracts a local .tar.gz. The symlink keeps the paths below version-independent.
ADD frontcache-console.tar.gz /opt/
RUN test -d "/opt/frontcache-console-${FRONTCACHE_VERSION}" \
      || { echo "ERROR: archive did not expand to /opt/frontcache-console-${FRONTCACHE_VERSION} - wrong --build-arg FRONTCACHE_VERSION?" >&2; exit 1; } \
 && ln -s "/opt/frontcache-console-${FRONTCACHE_VERSION}" /opt/frontcache-console

# Read-only copy of the shipped config, outside conf/ so a mount cannot hide it - the
# entrypoint seeds only what is missing, so an image upgrade can add new files to an
# existing conf volume.
RUN mkdir -p /opt/frontcache-defaults \
 && cp -a /opt/frontcache-console/conf /opt/frontcache-defaults/conf

COPY entrypoint.sh /opt/fc-console/entrypoint.sh
RUN chmod +x /opt/fc-console/entrypoint.sh /opt/frontcache-console/bin/frontcache-console

ENV FRONTCACHE_CONSOLE_PORT=7080
EXPOSE 7080

# /realtime is the console's landing view; / redirects to it.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS -o /dev/null "http://127.0.0.1:${FRONTCACHE_CONSOLE_PORT}/realtime"

ENTRYPOINT ["/opt/fc-console/entrypoint.sh"]
