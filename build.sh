#!/usr/bin/env bash
# Builds app/trunkdeck.jar from source. Needs a JDK 22+ (javac + jar) on PATH (the native engine uses the
# Java Foreign Function & Memory API, final since Java 22). "bash build.sh test" also runs the test suite
# (TEST_OPTS can pass -Djmbe=/path/jmbe.jar -Dtrunkdeck.lib=lib for the voice tests).
set -euo pipefail
cd "$(dirname "$0")"
JAVAC="${JAVAC:-javac}"; JAR="${JAR:-jar}"; JAVA="${JAVA:-java}"
rm -rf build/classes build/test && mkdir -p build/classes build/test
find src -name '*.java' > build/sources.txt
"$JAVAC" --release 22 -encoding UTF-8 -Xlint:-options -cp lib/flatlaf-3.5.4.jar -d build/classes @build/sources.txt
printf 'Main-Class: p25monitor.Main\nClass-Path: flatlaf-3.5.4.jar\nEnable-Native-Access: ALL-UNNAMED\nImplementation-Title: TrunkDeck\nImplementation-Version: 1.5.1\n' > build/manifest.txt
"$JAR" cfm build/trunkdeck.jar build/manifest.txt -C build/classes .
echo "Built build/trunkdeck.jar  (copy it to app/ next to flatlaf-3.5.4.jar; the other lib/*.jar files go to app/lib/)"
if [ "${1:-}" = "test" ]; then
  find test -name '*.java' > build/tests.txt
  "$JAVAC" --release 22 -encoding UTF-8 -cp build/classes:lib/flatlaf-3.5.4.jar -d build/test @build/tests.txt
  "$JAVA" --enable-native-access=ALL-UNNAMED ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.NativeTests
  "$JAVA" -Dtrunkdeck.lib=lib ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.OpenMhzTests
  "$JAVA" -Dtrunkdeck.lib=lib ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.BroadcastifyCallsTests
  "$JAVA" --enable-native-access=ALL-UNNAMED -Dtrunkdeck.lib=lib -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.RdioScannerTests   # Rdio Scanner uploads (1.4.8); -Djmbe=<jmbe jar> adds the real-engine upload checks
  "$JAVA" -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.DisplayBarsTests   # bar colours (1.4.9)
  "$JAVA" -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.DisplayOnlyTests   # Display only logic (1.5.0; the window part needs a display)
  "$JAVA" -Dtrunkdeck.lib=lib -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.SpeakerIsolationTests
  "$JAVA" -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.SpeakerQueueTests   # speaker Queue mode (1.4.2)
  "$JAVA" -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.SelfHealTests   # self-healing sample processing (1.4.3)
  "$JAVA" -Dtrunkdeck.lib=lib -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.LiveTalkgroupTests   # live talkgroup edits, feeds stay connected (1.4.4)
  "$JAVA" -Dtrunkdeck.lib=lib -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.FeedTagTests   # now-playing tags on feeds (1.5.1)
  "$JAVA" ${TEST_OPTS:-} -cp build/classes:build/test p25monitor.p25.CodeTablesTests   # coding tables built from the TIA-102 standards = the earlier tables (1.4.6)
  "$JAVA" -Dtrunkdeck.lib=lib ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.ViewModeTests   # Simple / Advanced view, colours, scan animation (1.4.5; needs a display, skipped when headless)
  "$JAVA" -Dtrunkdeck.lib=lib ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.TrayTests   # system tray (1.4.7): logic always; window and real tray parts need a display / a tray
  "$JAVA" --enable-native-access=ALL-UNNAMED -Dtrunkdeck.librtlsdr=../app/native/linux-x64/librtlsdr.so ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.sdr.RtlHealTests   # (1.4.3; skipped if librtlsdr is not loadable)
  "$JAVA" ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.AirspyFuzz 30   # Airspy DSP chain fuzz / stress (1.4.3)
  "$JAVA" -Dtrunkdeck.lib=lib ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.RealEotTests
  "$JAVA" -Dtrunkdeck.lib=lib ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.FeedTests   # needs ffmpeg/ffprobe (skipped otherwise)
  TD_TMP=$(mktemp -d)   # Airspy R2 (1.4.0): simulated USB device, never touches ~/.trunkdeck
  "$JAVA" -Djava.awt.headless=true -Dtrunkdeck.profile=$TD_TMP/rp ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.RadioAliasTests   # radio names (1.4.1); -DradiosFile=<your DSD+ .radios> to test a real file
  "$JAVA" --enable-native-access=ALL-UNNAMED -Dtrunkdeck.lib=lib -Djava.awt.headless=true -Dtrunkdeck.profile=$TD_TMP/p ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.AirspyTests
  "$JAVA" --enable-native-access=ALL-UNNAMED -Dtrunkdeck.lib=lib -Djava.awt.headless=true -Dtrunkdeck.profile=$TD_TMP/p ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.AirspyBench
  "$JAVA" --enable-native-access=ALL-UNNAMED -Dtrunkdeck.lib=lib -Djava.awt.headless=true -Dtrunkdeck.home=$TD_TMP -Dtrunkdeck.profile=$TD_TMP/p ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.WidebandSite airspy
  "$JAVA" --enable-native-access=ALL-UNNAMED -Dtrunkdeck.lib=lib -Djava.awt.headless=true -Dtrunkdeck.home=$TD_TMP -Dtrunkdeck.profile=$TD_TMP/p ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.WidebandSite mixed
fi
