#!/bin/bash

CONTAINER_ID=`docker ps -f name=frontcache-logstash -q`
echo $CONTAINER_ID

if [ -n "$CONTAINER_ID" ]; then
    echo "Stopping logstash container...."
    docker stop $CONTAINER_ID
else
  echo "Logstash container is not running"
fi