#!/bin/bash


CONTAINER_ID=`docker ps -a -f name=frontcache-server -q`
echo $CONTAINER_ID

if [ -n "$CONTAINER_ID" ]; then
    echo "Container exists."

    RUNNING_CONTAINER_ID=`docker ps -f name=frontcache-server -q`
    
    if [ -n "$RUNNING_CONTAINER_ID" ]; then
      echo "Container is already running. ID: $RUNNING_CONTAINER_ID"
    else
      echo "Container exists but stopped. Starting container... $RUNNING_CONTAINER_ID"    
      docker start $CONTAINER_ID    
    fi
  
else

  echo "FC container does not exists.. Running container"
  docker run --memory="1g"  -p 9080:9080   --name=frontcache-server frontcache/server
fi