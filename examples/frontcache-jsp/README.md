### 'frontcache-jsp' - Frontcache as Servlet Filter ###

Example project path: ./examples/frontcache-jsp

**Steps to run**

        git clone https://github.com/eternita/frontcache.git
        cd frontcache/examples/frontcache-jsp/
        ./gradlew clean appRun

The `appRun` task builds the war and runs it on an embedded Jetty 12 (Jakarta EE10) launcher
(`ExampleJettyServer`) on port 8080. Requires a **JDK 25** — Frontcache 2.6.0 is Java 25
bytecode and Jakarta EE 10.

`frontcache-core` comes from the published artifact (see the `repositories` block in
`build.gradle`), so this directory builds standalone: copy it anywhere and it still works.

point browser to http://localhost:8080/example/index.jsp

### Check logs to HTTP header for the first and second requests ###

**For the first request all includes are dynamic (from origin)**

![Alt](https://raw.githubusercontent.com/eternita/frontcache/master/examples/images/frontcache-jsp/headers-1-request.png "first request")

**For the second request 'User Profile' section is dynamic and all other includes are from cache**

![Alt](https://raw.githubusercontent.com/eternita/frontcache/master/examples/images/frontcache-jsp/headers-2-request.png "second request")

### Development steps ###

**Create regular JSP-based web application project**

![Alt](https://raw.githubusercontent.com/eternita/frontcache/master/examples/images/frontcache-jsp/jsp-web-project.png "Regular JSP-based web application project")

**Download/extract/copy FRONTCACHE_HOME directory to the project**

![Alt](https://raw.githubusercontent.com/eternita/frontcache/master/examples/images/frontcache-jsp/add-frontcache-home-dir.png "Add FRONTCACHE_HOME directory")


**Edit build.gradle**
 - add Frontcache Maven dependency
 - set environment variable to FRONTCACHE_HOME directory

![Alt](https://raw.githubusercontent.com/eternita/frontcache/master/examples/images/frontcache-jsp/edit-build-gradle.png "Edit build.gradle") 
 
**Add Frontcache Filter to web.xml**

![Alt](https://raw.githubusercontent.com/eternita/frontcache/master/examples/images/frontcache-jsp/edit-web-xml.png "Edit web.xml") 
