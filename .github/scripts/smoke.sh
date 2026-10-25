#!/usr/bin/env bash
# Installs the release APK on the emulator, launches it, and fails if the app crashes or isn't running.
# Saves screenshots and logcat to ci-output/ for the diagnostics release.
set -u
PKG=app.kassa.money
mkdir -p ci-output
adb logcat -c
# Upgrade path: start from the last published release so its database exists, then install this build over it.
if [ -f previous.apk ]; then
  adb install previous.apk > ci-output/install-previous.txt 2>&1 && \
    adb shell am start -W -n $PKG/.ui.MainActivity > /dev/null 2>&1 && sleep 10 && \
    adb exec-out screencap -p > ci-output/previous.png
  adb shell am force-stop $PKG
fi
adb install -r kassa.apk > ci-output/install.txt 2>&1 || { cat ci-output/install.txt; exit 1; }
adb shell am start -W -n $PKG/.ui.MainActivity > ci-output/start.txt 2>&1
sleep 15
adb exec-out screencap -p > ci-output/phone.png
# Approximate the Fold's inner screen and look again.
adb shell wm size 1812x2176
adb shell wm density 420
sleep 6
adb exec-out screencap -p > ci-output/unfolded.png
adb shell wm size reset
adb shell wm density reset
sleep 3
# Accessibility and theme checks: the largest font size the phone offers, then dark mode.
adb shell settings put system font_scale 2.0
sleep 5
adb exec-out screencap -p > ci-output/large-font.png
adb shell settings put system font_scale 1.0
adb shell cmd uimode night yes
sleep 5
adb exec-out screencap -p > ci-output/dark.png
adb shell cmd uimode night no
sleep 3
adb logcat -d > ci-output/logcat.txt
PID=$(adb shell pidof $PKG | tr -d '\r')
# Only Kassa's own crashes count: the emulator's Play services sometimes crash on their own.
if grep -A 1 "FATAL EXCEPTION" ci-output/logcat.txt | grep -q "Process: $PKG," || [ -z "$PID" ]; then
  echo "Kassa crashed or is not running"
  grep -A 40 "FATAL EXCEPTION" ci-output/logcat.txt | grep -A 39 "Process: $PKG," | head -80
  exit 1
fi
echo "Kassa is running as pid $PID"
