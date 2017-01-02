#!/usr/bin/jjs -fv
var cmd = "docker build -t frontcache/tomcat8 ."
print("Building ${cmd}");
var System = Java.type("java.lang.System");
$EXEC(cmd, System.in, System.out, System.err);