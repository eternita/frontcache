#!/usr/bin/jjs -fv
var images = ["fc-debian", "fc-java", "fc-tomcat8", "frontcache-server", "fc-elk"];
for each(image in images) {
    print("\n\n");
    print("Building ${image}");
    $ENV.PWD = image;
    load("${image}/build.js");
    print("Image ${image} built!");
}