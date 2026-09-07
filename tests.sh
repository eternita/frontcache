#!/bin/sh

# Runs the end-to-end suite. Can be invoked from anywhere; gradle runs from the
# repo root and the :frontcache-tests: subproject holds the servers + suite.
ROOT_DIR=$(cd "$(dirname "$0")" && pwd)
TESTS_DIR="$ROOT_DIR/frontcache-tests"
cd "$ROOT_DIR" || exit 1

# cleanup cache dirs & logs
# for filter
rm -rf "$TESTS_DIR"/FRONTCACHE_HOME_FILTER/cache/l2-lucene-index/*
rm -rf "$TESTS_DIR"/FRONTCACHE_HOME_FILTER/logs/*

# for standalone test server
rm -rf "$TESTS_DIR"/FRONTCACHE_HOME_STANDALONE/cache/l2-lucene-index/*
rm -rf "$TESTS_DIR"/FRONTCACHE_HOME_STANDALONE/logs/*
# fallback2.txt is fetched from its init URL at engine startup - but only when it is ABSENT
rm -f "$TESTS_DIR"/FRONTCACHE_HOME_FILTER/fallbacks/fallback2.txt
rm -f "$TESTS_DIR"/FRONTCACHE_HOME_STANDALONE/fallbacks/fallback2.txt
# set-config backs a config file up before writing it; the config-write tests restore the file
# itself, but the timestamped copies accumulate
rm -f "$TESTS_DIR"/FRONTCACHE_HOME_FILTER/conf/*.backup-*
rm -f "$TESTS_DIR"/FRONTCACHE_HOME_STANDALONE/conf/*.backup-*
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

# Stop the servers with SIGTERM first so their JVM shutdown hooks run — that is what
# flushes each one's jacoco .exec file. SIGKILL only as a fallback for a hung JVM.
echo "Stopping embedded Jetty servers ..."
for port in 9080 8080; do
	pids=$(lsof -ti tcp:$port)
	[ -n "$pids" ] || continue
	echo "$pids" | xargs kill 2>/dev/null
done
# Each server JVM flushes its jacoco .exec from a shutdown hook. Wait for both files to
# appear and stop growing, then force the JVMs down: they hold non-daemon threads and do
# not exit on their own after the hook runs, so waiting on the ports would only stall.
STANDALONE_EXEC="$TESTS_DIR/build/jacoco/standaloneJetty.exec"
WEBFILTER_EXEC="$TESTS_DIR/build/jacoco/webfilterJetty.exec"
prev=""
i=0
while [ $i -lt 20 ]; do
	if [ -f "$STANDALONE_EXEC" ] && [ -f "$WEBFILTER_EXEC" ]; then
		cur=$(( $(wc -c < "$STANDALONE_EXEC") + $(wc -c < "$WEBFILTER_EXEC") ))
		[ "$cur" = "$prev" ] && break
		prev=$cur
	fi
	sleep 1
	i=$((i + 1))
done
for port in 9080 8080; do
	pids=$(lsof -ti tcp:$port)
	[ -n "$pids" ] || continue
	echo "$pids" | xargs kill -9 2>/dev/null
done
echo "Embedded Jetty servers have been stopped"

# Coverage report. Runs AFTER the kill above, because the server JVMs only write their
# .exec files on shutdown — running it earlier would report 0% for all engine-side code.
./gradlew :frontcache-tests:jacocoIntegrationTestReport

# cleanup / stop gradle daemons
./gradlew -stop
