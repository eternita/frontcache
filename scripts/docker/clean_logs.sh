#!/usr/bin/env bash

CONTAINER_ID=`docker ps -a -f name=frontcache-logstash -q`
docker exec $CONTAINER_ID  curator --config /opt/curator/curator.yml  opt/curator/actions.yml