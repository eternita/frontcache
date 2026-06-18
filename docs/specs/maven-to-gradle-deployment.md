# Spec: Migrate artifact deployment from Maven (`pom.xml`) to Gradle (`build.gradle`)

## Background

The repository carries **two** build definitions:

- **`build.gradle`** — the primary build. Compiles every module (Java 11 toolchain),
  runs unit + integration tests, assembles WARs, and runs Gretty for local/e2e.
- **`pom.xml`** — does **not** build the app. Its only job is **deployment**:
  publishing the two library modules (`frontcache-core`, `frontcache-agent`) as
  Maven artifacts (jar + sources + javadoc + POM) to the Eternita S3 repository.

This is a split-brain setup: contributors build with Gradle but must drop to Maven to
release. The goal of this migration is to make **Gradle the single source of truth** by
moving the publishing concern out of `pom.xml` and into `build.gradle`, then retiring
`pom.xml`.

## What `pom.xml` does today (the deployment surface to replicate)

| Concern | Maven mechanism | Value |
|---|---|---|
| Coordinates | `groupId` / `artifactId` / `version` | `org.frontcache` / `frontcache` / `1.2.4` (parent; children inherit group+version) |
| Published modules | `<modules>` | `frontcache-core`, `frontcache-agent` **only** |
| POM metadata | `name`, `description`, `url` | "Web page/fragment cache with server side includes", https://www.frontcache.org |
| License | `<licenses>` | "Frontcache license", https://www.eternita.co/frontcache-license.html |
| Developers | `<developers>` | Dmitriy Pavlikovskiy, Serhiy Pavlikovskiy (Eternita) |
| SCM | `<scm>` | github.com/eternita/frontcache |
| Target repo | `<distributionManagement>` + `maven-s3-wagon` extension | `s3://repo.eternita.co/releases` and `.../snapshots` |
| Source jar | `maven-source-plugin` | attach `-sources.jar` |
| Javadoc jar | `maven-javadoc-plugin` (`-Xdoclint:none`) | attach `-javadoc.jar` |
| Compile level | `maven-compiler-plugin` | Java 11 — **already handled by the Gradle toolchain** |
| License header format | `maven-license-plugin` | **already handled by the `com.github.hierynomus.license` Gradle plugin** |

Only the rows **not** struck through above need new Gradle config. Compilation and
license-header formatting are already covered in `build.gradle`.

## Goal

Run a full release with a single command:

```sh
./gradlew publish        # or publishToMavenLocal for dry runs
```

…publishing `frontcache-core` and `frontcache-agent` (each with jar + sources +
javadoc + a complete POM) to the Eternita S3 repo, snapshots vs. releases selected by
the version suffix. After this works, delete `pom.xml`.

## Non-goals

- Publishing `frontcache-server`, `frontcache-console`, or `frontcache-tests` (they are
  apps/WARs, never published — keep it that way).
- Changing artifact coordinates, the license, or the S3 bucket layout.
- Touching the `prep_custom_maven_repo.sh` flat-directory repo flow (separate concern;
  see "Open questions").
- Migrating to Sonatype/Maven Central (the `pom.xml` only ever targeted the S3 repo).

## Design

### 1. Apply `maven-publish` to the published modules only

In `build.gradle`, define a reusable publishing block and apply it to **only** the two
library modules. Do **not** put it in the `subprojects {}` block, because that would try
to publish the WAR modules too.

```gradle
// applied to frontcache-core and frontcache-agent
configure([project(':frontcache-core'), project(':frontcache-agent')]) {
    apply plugin: 'maven-publish'

    java {
        withSourcesJar()      // replaces maven-source-plugin
        withJavadocJar()      // replaces maven-javadoc-plugin
    }

    // -Xdoclint:none equivalent — don't fail the build on javadoc lint
    tasks.withType(Javadoc).configureEach {
        options.addStringOption('Xdoclint:none', '-quiet')
    }

    publishing {
        publications {
            mavenJava(MavenPublication) {
                from components.java   // includes api/implementation deps in POM with correct scopes
                pom {
                    name = "${project.group}:${project.name}"
                    description = 'Web page/fragment cache with server side includes'
                    url = 'https://www.frontcache.org'
                    licenses {
                        license {
                            name = 'Frontcache license'
                            url  = 'https://www.eternita.co/frontcache-license.html'
                        }
                    }
                    developers {
                        developer { name = 'Dmitriy Pavlikovskiy'; email = 'pdaviti@gmail.com'; organization = 'Eternita'; organizationUrl = 'https://www.eternita.co' }
                        developer { name = 'Serhiy Pavlikovskiy'; email = 'pavlikovskiy@gmail.com'; organization = 'Eternita'; organizationUrl = 'https://www.eternita.co' }
                    }
                    scm {
                        connection = 'scm:git:git://github.com/eternita/frontcache.git'
                        developerConnection = 'scm:git:ssh://github.com:eternita/frontcache.git'
                        url = 'https://github.com/eternita/frontcache/tree/master'
                    }
                }
            }
        }
        repositories {
            maven {
                def releasesUrl  = 's3://repo.eternita.co/releases'
                def snapshotsUrl = 's3://repo.eternita.co/snapshots'
                url = version.toString().endsWith('SNAPSHOT') ? snapshotsUrl : releasesUrl
                credentials(AwsCredentials) {
                    accessKey = project.findProperty('awsAccessKey') ?: System.getenv('AWS_ACCESS_KEY_ID')
                    secretKey = project.findProperty('awsSecretKey') ?: System.getenv('AWS_SECRET_ACCESS_KEY')
                }
            }
        }
    }
}
```

### 2. S3 transport — no extra plugin needed

Maven needed the `maven-s3-wagon` extension to speak `s3://`. **Gradle's `maven-publish`
supports `s3://` repository URLs natively** via `AwsCredentials` — no third-party plugin
required. This is the key simplification of the migration.

### 3. Snapshot vs. release selection

Maven routed by `<snapshotRepository>` vs `<repository>` automatically based on whether
the version ended in `-SNAPSHOT`. Replicate with the `version.endsWith('SNAPSHOT')`
ternary on the repository `url` (shown above). Current version `1.2.4` (no suffix) →
`releases`.

### 4. POM dependency fidelity

`from components.java` on the `java-library` plugin emits dependencies into the generated
POM with correct scopes:
- `api` deps → `compile` scope (matters for `frontcache-core`'s many `api` deps and
  `frontcache-agent`'s httpclient `api` dep so downstream consumers get them transitively).
- `provided` is a custom configuration here, **not** a Maven scope — verify it is **not**
  leaked into the POM (it shouldn't be, since it's not wired into `components.java`).
  The old Maven POM listed no dependencies at all, so any deps in the new POM are an
  improvement, but confirm servlet/jsp `provided` deps stay out.

### 5. Credentials

The old flow relied on `~/.m2/settings.xml` server entries for the S3 wagon. The current
`settings.xml` has no `eternita-s3-*` entries, so credentials likely come from the AWS
default chain / env vars. New flow reads `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`
(or `-PawsAccessKey=` / `-PawsSecretKey=`, or `~/.gradle/gradle.properties`). Document the
chosen mechanism in the README/release notes.

## Implementation steps

1. Add the `configure([...])` publishing block to `build.gradle` (section 1 above).
2. `./gradlew :frontcache-core:publishToMavenLocal :frontcache-agent:publishToMavenLocal`
   and inspect the generated POMs under `~/.m2/repository/org/frontcache/...` — verify
   coordinates, metadata, license, developers, scm, and dependency scopes.
3. Confirm `-sources.jar` and `-javadoc.jar` are produced and javadoc does not fail on lint.
4. Do a real `./gradlew publish` against the S3 repo with credentials wired in (or a
   throwaway test bucket first), and verify the objects land under `releases/`.
5. Delete `pom.xml`. Grep the repo for references to it (CI, `prep_custom_maven_repo.sh`,
   docs, `tests.sh`) and update or remove them.
6. Update `CLAUDE.md` — the "Build & test" section currently says the Maven `pom.xml`
   builds core+agent for publishing; change it to point at `./gradlew publish`.

## Verification / acceptance criteria

- `./gradlew publishToMavenLocal` produces, for **both** modules: `-1.2.4.jar`,
  `-1.2.4-sources.jar`, `-1.2.4-javadoc.jar`, and a `.pom` containing the name,
  description, url, license, developers, and scm from the table above.
- `frontcache-server`, `frontcache-console`, `frontcache-tests` produce **no** publish
  tasks / artifacts.
- `./gradlew publish` uploads to `s3://repo.eternita.co/releases` (version `1.2.4`).
- A SNAPSHOT version routes to `s3://repo.eternita.co/snapshots` instead.
- `./gradlew build` and `./tests.sh` still pass unchanged.
- `pom.xml` is removed and no remaining file references it.

## Open questions

1. **Credentials mechanism** — confirm whether the release machine uses env vars, an AWS
   profile, or explicit Gradle properties, and standardize on one.
2. **`prep_custom_maven_repo.sh`** — it currently parses both `pom.xml` and `build.gradle`
   to build a flat GitHub-hosted repo. Removing `pom.xml` breaks its `xml_value` parsing.
   Decide whether to (a) update the script to read coordinates from Gradle only, or
   (b) retire it in favor of `publishToMavenLocal` + the S3 repo. Out of scope for this
   spec but must be sequenced with step 5.
3. **Javadoc strictness** — confirm `-Xdoclint:none` is still wanted, or take the
   opportunity to fix javadoc warnings.
