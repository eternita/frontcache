# Frontcache with Docker
## Build Frontcache
got to `frontcache`
run `./gradlew build`

## Update settings
Update settings in /frontcache-server/FRONTCACHE_HOME/conf/frontcache.properties

## Build docker image
in `frontcache/scripts/docker/frontcache-server/`
run ./build.sh

## Start Docker container
in `frontcache/scripts/docker/frontcache-server/`
run ./start_fc.sh

## Open in Browser
http://localhost:9080/en/welcome.htm
 


