#!/bin/bash

CONTAINER_ID=`docker ps -f name=frontcache-server -q`
echo $CONTAINER_ID

if [ -n "$CONTAINER_ID" ]; then
    echo "Stopping container...."
    docker stop $CONTAINER_ID
else
  echo "Container is not running"
fi