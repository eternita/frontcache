#!/bin/sh

./apache-jmeter-3.1/bin/jmeter -n -JTestHost=www.hobbyray.com -JnThreads=2  -t ./scripts/SiteMapTest.jmx -l ./scripts/SitemapTestResults.jtl