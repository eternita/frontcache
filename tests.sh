#!/bin/sh

./gradlew clean :frontcache-tests:startStandaloneFrontcache
./gradlew :frontcache-tests:end2endTests
./gradlew :frontcache-tests:stopStandaloneFrontcacheByPort
