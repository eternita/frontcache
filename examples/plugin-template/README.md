# Frontcache plugin template

A starting point for a Frontcache plugin: code from its own jar that a node loads from
`FRONTCACHE_HOME/plugins/<id>/`, built against the Apache-2.0 plugin API (`org.frontcache:frontcache-plugin-api`)
and tested with the plugin testkit (`org.frontcache:frontcache-plugin-testkit`). Copy this directory into a
repository of your own, rename, and replace the example.

The example plugin (`id = example`) has one of each kind a plugin may contribute, each kept tiny:

| kind | what | where |
|---|---|---|
| **decide** (E3) | `ext:path-depth:>4` - a predicate for guard and bot rules: how many segments the path has | `PathDepthPredicate` |
| **observe** (E1) | counts the guard's matches by disposition, from `GuardMatched` events | `GuardMatchCounter` |
| **serve** (E2) | `GET /fc-x/example/` answers those counts as JSON - to the node itself only | `ExamplePlugin` |

and a config, `conf/example.conf` (`max-depth = 32`), read whole or refused whole.

## Build

Java 25 and Gradle 9.7 (run `gradle wrapper --gradle-version 9.7.0` once and commit the wrapper):

```sh
./gradlew build          # compile, test (PluginContract included), build/libs/frontcache-plugin-example-0.1.0.jar
./gradlew sha256         # the jar's SHA-256, for a node that pins it
```

`frontcacheVersion` in `gradle.properties` is the Frontcache release the API and the testkit are taken from
(`https://repo.eternita.co/maven2`). Against an unreleased core: `./gradlew publishToMavenLocal` in the core
checkout, then `./gradlew build -PfrontcacheMavenLocal`.

## Install on a node

1. Put the jar in `FRONTCACHE_HOME/plugins/example/` (the directory is the manifest's `id`). One shaded jar is
   what this build makes; a plugin directory may also hold the plugin's own library jars.
2. Enable it in `conf/frontcache.properties` - a jar in a directory changes nothing by itself:

   ```properties
   front-cache.plugins = example
   # optional: refuse to load a jar that is not this one
   front-cache.plugins.example.sha256 = <./gradlew sha256>
   ```
3. Its config, when the manifest names one, is `conf/example.conf` - editable from the console's Plugin Configs
   screen, applied with `reload-plugin-config?plugin=example`.
4. Restart the node (plugin code never reloads in place; its config does). `get-node-stats` lists it under
   `plugins`, with its kinds, state and config status.

Use the predicate in `conf/guard-rules.conf`:

```
too-deep | ext:path-depth:>12 | reject:404
```

## The class-loader rule

A dropped-in plugin gets a class loader of its own. Its parent shows **only** the JDK, the plugin API
(`org.frontcache.plugin`, `.event`, `.serve`, `.decide`, and `org.frontcache.io.ActionResponse`), Typesafe Config
(`com.typesafe.config`), Jackson's annotations (`com.fasterxml.jackson.annotation`), slf4j (`org.slf4j`) and
Micrometer (`io.micrometer`). So:

- those are `compileOnly` in `build.gradle` - never in your jar;
- **everything else you use must be inside your jar** - `implementation` dependencies are shaded into it by the
  Shadow plugin. `jackson-databind` is *not* among what the node shows: parse JSON yourself or shade it (relocated);
- your plugin never sees the engine, another plugin's classes or the core's internals - only the API's views.

## What a plugin must be

The API's javadoc is the contract; the testkit's `PluginContract` checks it (`ExamplePluginTest` extends it):

- **The manifest** (`META-INF/frontcache-plugin.properties`) names the entry class and declares the kinds; the node
  refuses a plugin that contributes a kind it did not declare. A `.properties` file has no trailing comments.
- **Constructor cheap, `start` quick.** Background work goes on `context.scheduler()`, scheduled in `start` - its
  tasks are cancelled when the plugin stops, a reload included.
- **A predicate runs on the request thread**: pure, from memory, no I/O, answering (not throwing) for a view whose
  every value is null. Each call is timed; a plugin whose p99 is over its budget (200 microseconds by default) is
  switched off for five minutes.
- **A listener runs on its own thread**, after the fact; one that blocks only fills its own queue.
- **An endpoint answers `/fc-x/<id>/` only**, through a narrowed view (no `Authorization`, no cookies but those the
  manifest names, no `x-frontcache-*`); its response is buffered, and a throw answers 503.
- **A config is read whole or refused whole**: parse into a new value, apply it only when all of it is accepted, and
  name every reason - never quoting a secret.
- **An event's `clientKey` is an address - personal data.** Keep `context.hashClientKey(key)`, not the key.
