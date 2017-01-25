#!/bin/sh

# cleanup cache dirs & logs
# for filter 
rm -rf ./frontcache-tests/FRONTCACHE_HOME_FILTER/cache/l2-lucene-index/*
rm -rf ./frontcache-tests/FRONTCACHE_HOME_FILTER/logs/*

# for standalone test server 
rm -rf ./frontcache-tests/FRONTCACHE_HOME_STANDALONE/cache/l2-lucene-index/*
rm -rf ./frontcache-tests/FRONTCACHE_HOME_STANDALONE/logs/*
# end cleanup cache dirs & logs


./gradlew clean :frontcache-tests:startStandaloneFrontcache
./gradlew :frontcache-tests:end2endTests
./gradlew :frontcache-tests:stopStandaloneFrontcacheByPort
