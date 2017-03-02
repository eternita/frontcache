#!/bin/bash

CONTAINER_ID=`docker ps -a -f name=frontcache-server -q`

if [ -n "$CONTAINER_ID" ]; then
    echo "Container exists."

  docker stop $CONTAINER_ID
  docker rm $CONTAINER_ID
fi


docker build -t frontcache/server .