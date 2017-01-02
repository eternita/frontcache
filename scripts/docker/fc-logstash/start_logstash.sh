#!/bin/bash

CONTAINER_ID=`docker ps -a -f name=frontcache-logstash -q`
echo $CONTAINER_ID

mkdir -p /tmp/frontcache/logs


if [ -n "$CONTAINER_ID" ]; then
    echo "Container exists."

    RUNNING_CONTAINER_ID=`docker ps -f name=frontcache-logstash -q`
    
    if [ -n "$RUNNING_CONTAINER_ID" ]; then
      echo "Container is already running. ID: $RUNNING_CONTAINER_ID"
    else
      echo "Container exists but stopped. Starting container... $RUNNING_CONTAINER_ID"    
      docker start $CONTAINER_ID    
    fi
  
else

  echo "Container does not exists.. Running container"
  docker run -v /tmp/frontcache/logs:/var/log/fc  -p 5044:5044 -e LS_HEAP_SIZE="1g" -e LS_OPTS="--no-auto-reload"  --name=frontcache-logstash frontcache/logstash
fi