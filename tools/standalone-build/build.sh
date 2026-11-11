#!/bin/sh
# Standalone build without Maven: compiles the bundle against API stubs that match the signatures of
# openHAB 5.2 (folder stubs/) and packs it with the component XML and manifest from resources/.
# The regular build is the Maven build inside openhab-addons (see .github/workflows/build.yml).
#
# Requirements: JDK 21, gson and slf4j-api jars (put them into lib/ or set GSON / SLF4J).
set -e
cd "$(dirname "$0")"
BUNDLE=../../bundles/org.openhab.binding.schulmanager
GSON=${GSON:-lib/gson.jar}
SLF4J=${SLF4J:-lib/slf4j-api.jar}
VERSION=${VERSION:-5.2.4}
QUALIFIER=$(date -u +%Y%m%d%H%M)
OUT=build
rm -rf $OUT && mkdir -p $OUT/stubs $OUT/classes $OUT/jar dist
javac --release 21 -nowarn -d $OUT/stubs $(find stubs -name "*.java")
javac --release 21 -nowarn -encoding UTF-8 -d $OUT/classes -cp "$OUT/stubs:$GSON:$SLF4J" \
  $(find $BUNDLE/src/main/java -name "*.java")
cp -r $OUT/classes/. $OUT/jar/
cp -r $BUNDLE/src/main/resources/OH-INF $OUT/jar/
cp -r resources/OSGI-INF $OUT/jar/
cp $BUNDLE/NOTICE $OUT/jar/
sed "s/^Bundle-Version: .*/Bundle-Version: $VERSION.$QUALIFIER/" resources/META-INF/MANIFEST.MF > $OUT/MANIFEST.MF
jar --create --file dist/org.openhab.binding.schulmanager-$VERSION.jar --manifest $OUT/MANIFEST.MF -C $OUT/jar .
echo "done: $(pwd)/dist/org.openhab.binding.schulmanager-$VERSION.jar"
