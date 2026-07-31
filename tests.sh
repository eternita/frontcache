#!/bin/sh

# cleanup cache dirs & logs
# for filter
rm -rf ./frontcache-tests/FRONTCACHE_HOME_FILTER/cache/l2-lucene-index/*
rm -rf ./frontcache-tests/FRONTCACHE_HOME_FILTER/logs/*

# for standalone test server
rm -rf ./frontcache-tests/FRONTCACHE_HOME_STANDALONE/cache/l2-lucene-index/*
rm -rf ./frontcache-tests/FRONTCACHE_HOME_STANDALONE/logs/*
# end cleanup cache dirs & logs

# stop gradle daemons
./gradlew -stop

# Start the two embedded Jetty 12 servers:
#   FC webfilter + web app (:8080)  <-  FC standalone reverse-proxy (:9080)
# Each start task blocks until the server prints its "started successfully" line,
# then leaves the server JVM running in the background.
./gradlew clean :frontcache-tests:startWebfilterFrontcache
./gradlew :frontcache-tests:startStandaloneFrontcache

./gradlew :frontcache-tests:end2endTests

echo "Stopping embedded Jetty servers ..."
lsof -ti tcp:9080 | xargs kill -9 2>/dev/null
lsof -ti tcp:8080 | xargs kill -9 2>/dev/null
echo "Embedded Jetty servers have been stopped"

# cleanup / stop gradle daemons
./gradlew -stop
