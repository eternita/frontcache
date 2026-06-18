#!/bin/sh
#
# prep_custom_maven_repo.sh
#
# Prepare a custom Maven repository (a plain directory tree of artifacts +
# generated POMs + checksums)
#
# It pulls the required coordinates (groupId, version, modules) from pom.xml
# and build.gradle, builds the module JARs with Gradle, then runs
# `mvn install:install-file` for each module into a local repository directory.
#
# Usage:
#   ./prep_custom_maven_repo.sh [output-repo-dir]
#
# Default output dir: ./repository
#
# After it runs, point consumers at the repo with e.g.:
#   repositories {
#       maven { url 'https://raw.githubusercontent.com/eternita/frontcache/repository/' }
#   }
#
set -e

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR"

POM_FILE="$SCRIPT_DIR/pom.xml"
GRADLE_FILE="$SCRIPT_DIR/build.gradle"
REPO_DIR="${1:-$SCRIPT_DIR/repository}"

# ---- helpers ---------------------------------------------------------------

die() { echo "ERROR: $*" >&2; exit 1; }

# Extract the text of the first <tag>...</tag> occurrence from a file.
xml_value() {
	tag="$1"; file="$2"
	# grab first <tag>value</tag>, strip tags and surrounding whitespace
	grep -o "<$tag>[^<]*</$tag>" "$file" | head -n 1 \
		| sed -e "s/<$tag>//" -e "s|</$tag>||" -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

[ -f "$POM_FILE" ]    || die "pom.xml not found at $POM_FILE"
[ -f "$GRADLE_FILE" ] || die "build.gradle not found at $GRADLE_FILE"

# ---- pull required variables ----------------------------------------------

# groupId / version from pom.xml (the parent/aggregator coordinates).
GROUP_ID=$(xml_value groupId "$POM_FILE")
VERSION=$(xml_value version "$POM_FILE")

# Cross-check the version declared in build.gradle (subprojects { version = ... }).
GRADLE_VERSION=$(grep -E "^[[:space:]]*version[[:space:]]*=" "$GRADLE_FILE" \
	| head -n 1 | sed -E "s/.*version[[:space:]]*=[[:space:]]*'([^']*)'.*/\1/")

# Modules to publish — the <module> entries listed in pom.xml.
MODULES=$(grep -o "<module>[^<]*</module>" "$POM_FILE" \
	| sed -e 's/<module>//' -e 's|</module>||' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')

[ -n "$GROUP_ID" ] || die "could not determine groupId from pom.xml"
[ -n "$VERSION" ]  || die "could not determine version from pom.xml"
[ -n "$MODULES" ]  || die "could not determine modules from pom.xml"

if [ -n "$GRADLE_VERSION" ] && [ "$GRADLE_VERSION" != "$VERSION" ]; then
	echo "WARNING: version mismatch — pom.xml=$VERSION build.gradle=$GRADLE_VERSION (using $VERSION)" >&2
fi

echo "=========================================================="
echo " groupId   : $GROUP_ID"
echo " version   : $VERSION"
echo " modules   : $(echo $MODULES | tr '\n' ' ')"
echo " repo dir  : $REPO_DIR"
echo "=========================================================="

# ---- build the module JARs -------------------------------------------------

GRADLE_TASKS=""
for m in $MODULES; do
	GRADLE_TASKS="$GRADLE_TASKS :$m:build"
done

echo ">>> Building module JARs with Gradle ..."
./gradlew $GRADLE_TASKS

# ---- install each module into the custom repo ------------------------------

mkdir -p "$REPO_DIR"

for m in $MODULES; do
	ARTIFACT_ID="$m"
	JAR="$SCRIPT_DIR/$m/build/libs/$ARTIFACT_ID-$VERSION.jar"
	MODULE_POM="$SCRIPT_DIR/$m/pom.xml"

	[ -f "$JAR" ] || die "expected JAR not found: $JAR (did the Gradle build produce it?)"

	echo ">>> Installing $GROUP_ID:$ARTIFACT_ID:$VERSION into $REPO_DIR"

	# Optional sources / javadoc / pom — included only if present.
	EXTRA=""
	SOURCES_JAR="$SCRIPT_DIR/$m/build/libs/$ARTIFACT_ID-$VERSION-sources.jar"
	JAVADOC_JAR="$SCRIPT_DIR/$m/build/libs/$ARTIFACT_ID-$VERSION-javadoc.jar"
	[ -f "$SOURCES_JAR" ] && EXTRA="$EXTRA -Dsources=$SOURCES_JAR"
	[ -f "$JAVADOC_JAR" ] && EXTRA="$EXTRA -Djavadoc=$JAVADOC_JAR"

	if [ -f "$MODULE_POM" ]; then
		EXTRA="$EXTRA -DpomFile=$MODULE_POM"
		GEN_POM=""
	else
		GEN_POM="-DgeneratePom=true"
	fi

	mvn install:install-file \
		-DgroupId="$GROUP_ID" \
		-DartifactId="$ARTIFACT_ID" \
		-Dversion="$VERSION" \
		-Dfile="$JAR" \
		-Dpackaging=jar \
		$GEN_POM \
		-DlocalRepositoryPath="$REPO_DIR" \
		-DcreateChecksum=true \
		$EXTRA
done

echo "=========================================================="
echo " Done. Custom Maven repository prepared under:"
echo "   $REPO_DIR"
echo
echo " Layout: $REPO_DIR/$(echo $GROUP_ID | tr '.' '/')/<artifact>/$VERSION/"
echo
echo "=========================================================="
