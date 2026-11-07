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
printf 'Main-Class: p25monitor.Main\nClass-Path: flatlaf-3.5.4.jar\nEnable-Native-Access: ALL-UNNAMED\nImplementation-Title: TrunkDeck\nImplementation-Version: 1.7.2\n' > build/manifest.txt
# RadioReference app key (1.6.0): only when RR_APP_KEY is set while building, written into the built jar only (never into
# src/ or the git tree). Stored obscured (base64 of the reversed key) - any key inside a distributed binary can be read out.
rm -f build/classes/p25monitor/rr/appkey.dat
if [ -n "${RR_APP_KEY:-}" ]; then
  mkdir -p build/classes/p25monitor/rr
  K=$(printf '%s' "$RR_APP_KEY" | tr -d ' \t\r\n'); R=""; for ((i=${#K}-1; i>=0; i--)); do R+="${K:i:1}"; done   # reversed (plain bash: Git Bash has no rev)
  printf '%s' "$R" | base64 | tr -d '\r\n' > build/classes/p25monitor/rr/appkey.dat; unset K R
  echo "RadioReference app key from RR_APP_KEY built into build/trunkdeck.jar (not written to the source tree)"
fi
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
  "$JAVA" -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.SignalMeterTests   # control-channel signal meter: scale, text, glass
  "$JAVA" -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.ControlSignalTests   # control-tuner level only; no invented reading
  "$JAVA" -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.DisplayOnlyTests   # Display only logic (1.5.0; the window part needs a display)
  "$JAVA" -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.lan.LanTests   # home-network page: switch, password, private addresses, real start/stop/mute
  "$JAVA" -Dtrunkdeck.lib=lib -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.SpeakerIsolationTests
  "$JAVA" -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.SpeakerQueueTests   # speaker Queue mode (1.4.2)
  "$JAVA" -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.SelfHealTests   # self-healing sample processing (1.4.3)
  "$JAVA" -Dtrunkdeck.lib=lib -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.LiveTalkgroupTests   # live talkgroup edits, feeds stay connected (1.4.4)
  "$JAVA" -Dtrunkdeck.lib=lib -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.FeedTagTests   # now-playing tags on feeds (1.5.1)
  "$JAVA" -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test p25monitor.rr.RrTests   # RadioReference setup against a mock SOAP server (1.6.0)
  "$JAVA" --enable-native-access=ALL-UNNAMED -Dtrunkdeck.lib=lib -Djava.awt.headless=true -Dtrunkdeck.src=. ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.SoapyTests   # SDRplay/HackRF/LimeSDR/USRP via SoapySDR with simulated modules (1.7.0); -Dsoapy.prefix=<conda env with SoapySDR 0.8 + g++> (default $CONDA_PREFIX), skipped otherwise
  "$JAVA" ${TEST_OPTS:-} -cp build/classes:build/test p25monitor.p25.CodeTablesTests   # coding tables built from the TIA-102 standards = the earlier tables (1.4.6)
  "$JAVA" -Dtrunkdeck.lib=lib ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.ViewModeTests   # Simple / Advanced view, colours, scan animation (1.4.5; needs a display, skipped when headless)
  "$JAVA" -Dtrunkdeck.lib=lib -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.PlaybackTests   # in-app playback of saved MP3/WAV calls (the window part needs a display)
  "$JAVA" -Dtrunkdeck.lib=lib ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.TrayTests   # system tray (1.4.7): logic always; window and real tray parts need a display / a tray
  "$JAVA" --enable-native-access=ALL-UNNAMED -Dtrunkdeck.librtlsdr=../app/native/linux-x64/librtlsdr.so ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.sdr.RtlHealTests   # (1.4.3; skipped if librtlsdr is not loadable)
  "$JAVA" ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.AirspyFuzz 30   # Airspy DSP chain fuzz / stress (1.4.3)
  "$JAVA" -Dtrunkdeck.lib=lib ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.RealEotTests
  "$JAVA" -Dtrunkdeck.lib=lib ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.FeedTests   # needs ffmpeg/ffprobe (skipped otherwise)
  TD_TMP=$(mktemp -d)   # Airspy R2 (1.4.0): simulated USB device, never touches ~/.trunkdeck
  "$JAVA" -Djava.awt.headless=true -Dtrunkdeck.profile=$TD_TMP/rp ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.RadioAliasTests   # radio names (1.4.1); -DradiosFile=<your DSD+ .radios> to test a real file
  "$JAVA" -Djava.awt.headless=true ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.TalkgroupShareTests   # one talkgroup list for every site of a radio system
  "$JAVA" --enable-native-access=ALL-UNNAMED -Dtrunkdeck.lib=lib -Djava.awt.headless=true -Dtrunkdeck.profile=$TD_TMP/p ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.AirspyTests
  "$JAVA" --enable-native-access=ALL-UNNAMED -Dtrunkdeck.lib=lib -Djava.awt.headless=true -Dtrunkdeck.profile=$TD_TMP/p ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.nativeengine.AirspyBench
  "$JAVA" --enable-native-access=ALL-UNNAMED -Dtrunkdeck.lib=lib -Djava.awt.headless=true -Dtrunkdeck.home=$TD_TMP -Dtrunkdeck.profile=$TD_TMP/p ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.WidebandSite airspy
  "$JAVA" --enable-native-access=ALL-UNNAMED -Dtrunkdeck.lib=lib -Djava.awt.headless=true -Dtrunkdeck.home=$TD_TMP -Dtrunkdeck.profile=$TD_TMP/p ${TEST_OPTS:-} -cp build/classes:build/test:lib/flatlaf-3.5.4.jar p25monitor.WidebandSite mixed
fi
