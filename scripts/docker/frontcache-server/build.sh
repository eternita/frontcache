#!/bin/bash

CONTAINER_ID=`docker ps -a -f name=frontcache-server -q`

if [ -n "$CONTAINER_ID" ]; then
    echo "Container exists."

    docker stop $CONTAINER_ID
    docker rm $CONTAINER_ID
fi

fc_version=1.2.0
server_war=$(find ../../../frontcache-server/build/libs/  -name 'frontcache-server-*.war')
console_war=$(find ../../../frontcache-console/build/libs/  -name 'frontcache-console-*.war')

mkdir -p dist
if [ -e "$server_war" ]; then
    echo "Local file exists " $server_war
    cp $server_war dist/frontcache-server.war
else
    echo "Local file not found. Downloading from repository....."
    curl -o dist/frontcache-dist.zip -L http://static.frontcache.io/download/frontcache/frontcache-${fc_version}/frontcache-${fc_version}.zip
    unzip -o dist/frontcache-dist.zip -d dist/
    cp  dist/frontcache-${fc_version}/dist/frontcache-server-${fc_version}.war dist/frontcache-server.war
    cp  dist/frontcache-${fc_version}/dist/frontcache-console-${fc_version}.war dist/frontcache-console.war
fi;

if [ -e "$console_war" ]; then
    echo "Local file exists " $console_war
    cp $console_war dist/frontcache-console.war
fi;


docker build -t frontcache/server .