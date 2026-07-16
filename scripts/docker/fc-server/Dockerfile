# fc-server: nginx front door + Frontcache standalone server in one container.
#
# Mirrors the AWS single-host install (scripts/bash/install-frontcache-server-remote.sh
# + configure-nginx-remote.sh):
#   * Frontcache runs on embedded Jetty 12 (HTTP only) on 9080
#   * nginx terminates TLS on 443, serves 80, proxies / -> Frontcache and the
#     origin paths -> $ORIGIN_HOST
#
# The build context is the REPO ROOT (see fc-server/docker-compose.yml `context: ../../..`)
# so we can COPY the assembled frontcache-server/ tree. Build it first with
#   ./gradlew :frontcache-server:build
# (start-fc-server.sh does this for you).
FROM eclipse-temurin:21-jdk

# nginx for the public front door; openssl for keystore -> PEM extraction at startup.
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends nginx openssl \
 && rm -rf /var/lib/apt/lists/*

# The assembled standalone server: the launcher (server/bin/frontcache) resolves
# FRONTCACHE_HOME as ../FRONTCACHE_HOME, so both dirs sit under /opt/frontcache-server.
# We copy only what the launcher needs — not build/ or src/.
COPY frontcache-server/server/         /opt/frontcache-server/server/
COPY frontcache-server/FRONTCACHE_HOME/ /opt/frontcache-server/FRONTCACHE_HOME/

# entrypoint renders the nginx site config from env, extracts TLS, and runs both processes.
COPY scripts/docker/fc-server/entrypoint.sh /opt/fc-server/entrypoint.sh

RUN chmod +x /opt/fc-server/entrypoint.sh /opt/frontcache-server/server/bin/frontcache

# 80/443 public (nginx). 9080 stays internal (Frontcache HTTP).
EXPOSE 80 443

ENTRYPOINT ["/opt/fc-server/entrypoint.sh"]
