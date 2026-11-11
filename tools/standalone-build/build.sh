#!/bin/sh
# Standalone build without Maven: compiles the bundle against API stubs that match the signatures of
# openHAB 5.2 (folder stubs/) and packs it with the component XML and manifest from resources/.
# The regular build is the Maven build inside openhab-addons (see .github/workflows/build.yml).
#
# Requirements: JDK 21 and these jars in lib/ (or set the variables):
#   gson.jar, slf4j-api.jar and Jetty 9.4 jetty-client.jar, jetty-http.jar, jetty-io.jar, jetty-util.jar
#   (all of them are in an openHAB installation under runtime/system).
set -e
cd "$(dirname "$0")"
BUNDLE=../../bundles/org.openhab.binding.schulmanager
GSON=${GSON:-lib/gson.jar}
SLF4J=${SLF4J:-lib/slf4j-api.jar}
JETTY=${JETTY:-lib/jetty-client.jar:lib/jetty-http.jar:lib/jetty-io.jar:lib/jetty-util.jar}
VERSION=${VERSION:-5.2.4}
QUALIFIER=$(date -u +%Y%m%d%H%M)
OUT=build
rm -rf $OUT && mkdir -p $OUT/stubs $OUT/classes $OUT/jar dist
javac --release 21 -nowarn -cp "$JETTY" -d $OUT/stubs $(find stubs -name "*.java")
javac --release 21 -nowarn -encoding UTF-8 -d $OUT/classes -cp "$OUT/stubs:$GSON:$SLF4J:$JETTY" \
  $(find $BUNDLE/src/main/java -name "*.java")
cp -r $OUT/classes/. $OUT/jar/
cp -r $BUNDLE/src/main/resources/OH-INF $OUT/jar/
cp -r resources/OSGI-INF $OUT/jar/
cp $BUNDLE/NOTICE $OUT/jar/
sed "s/^Bundle-Version: .*/Bundle-Version: $VERSION.$QUALIFIER/" resources/META-INF/MANIFEST.MF > $OUT/MANIFEST.MF
jar --create --file dist/org.openhab.binding.schulmanager-$VERSION.jar --manifest $OUT/MANIFEST.MF -C $OUT/jar .
echo "done: $(pwd)/dist/org.openhab.binding.schulmanager-$VERSION.jar"
