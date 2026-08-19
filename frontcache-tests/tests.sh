#!/bin/sh

# Runs the end-to-end suite. Can be invoked from anywhere; gradle runs from the
# repo root and the :frontcache-tests: subproject holds the servers + suite.
TESTS_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT_DIR=$(cd "$TESTS_DIR/.." && pwd)
cd "$ROOT_DIR" || exit 1

# cleanup cache dirs & logs
# for filter
rm -rf "$TESTS_DIR"/FRONTCACHE_HOME_FILTER/cache/l2-lucene-index/*
rm -rf "$TESTS_DIR"/FRONTCACHE_HOME_FILTER/logs/*

# for standalone test server
rm -rf "$TESTS_DIR"/FRONTCACHE_HOME_STANDALONE/cache/l2-lucene-index/*
rm -rf "$TESTS_DIR"/FRONTCACHE_HOME_STANDALONE/logs/*
# end cleanup cache dirs & logs

# stop gradle daemons
./gradlew -stop

# Start the two embedded Jetty 12 servers:
#   FC webfilter + web app (:8080)  <-  FC standalone reverse-proxy (:9080)
# Each start task blocks until the server prints its "started successfully" line,
# then leaves the server JVM running in the background.
./gradlew :frontcache-tests:clean :frontcache-tests:startWebfilterFrontcache
./gradlew :frontcache-tests:startStandaloneFrontcache

./gradlew :frontcache-tests:end2endTests

echo "Stopping embedded Jetty servers ..."
lsof -ti tcp:9080 | xargs kill -9 2>/dev/null
lsof -ti tcp:8080 | xargs kill -9 2>/dev/null
echo "Embedded Jetty servers have been stopped"

# cleanup / stop gradle daemons
./gradlew -stop
