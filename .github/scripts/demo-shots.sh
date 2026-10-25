#!/usr/bin/env bash
# Screenshots of the real screens with the demo household, from the debug build: folded, unfolded, dark.
# They land in ci-output/ (the ci-<run> release) so a change can be checked by eye before it ships.
set -u
DPKG=app.kassa.money.debug
ACT=$DPKG/app.kassa.money.ui.MainActivity
mkdir -p ci-output
./gradlew --no-daemon :app:installDebug > ci-output/demo-install.log 2>&1 || { tail -20 ci-output/demo-install.log; exit 1; }
# Drags up from 3/4 to 1/4 of the screen's height, whatever size the emulator is.
scroll() {
  local wh w h
  wh=$(adb shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*'); w=${wh%x*}; h=${wh#*x}
  adb shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 4)) 800
}
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "ci-output/demo-$1.png"; }
# Taps the first clickable element whose text or description (or a child's) is exactly $1.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml ci-output/ui.xml > /dev/null 2>&1
  local xy
  xy=$(python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1]
root = ET.parse('ci-output/ui.xml').getroot()
parent = {c: p for p in root.iter() for c in p}
best = None
for n in root.iter('node'):
    if want not in (n.get('text'), n.get('content-desc')): continue
    m = n
    while m is not None and m.get('clickable') != 'true': m = parent.get(m)
    if m is not None: best = m; break
    best = best or n
if best is not None:
    a, b, c, d = map(int, re.findall(r'\d+', best.get('bounds')))
    print((a + c) // 2, (b + d) // 2)
PY
)
  [ -n "$xy" ] && adb shell input tap $xy || echo "No \"$1\" on screen"
}
adb shell am start -W -n $ACT --ez demo true > /dev/null 2>&1
shot 01-overview 14
scroll
shot 02-overview-scrolled
tap "Accounts"; shot 03-accounts
tap "Overview"
# The Fold's cover screen: Overview should fit with no scrolling.
adb shell wm size 1248x1972; adb shell wm density 420
shot 03b-cover 6
scroll; shot 03c-cover-after-swipe
adb shell wm size reset; adb shell wm density reset
sleep 3
# The Fold's inner screen.
adb shell wm size 1812x2176; adb shell wm density 360
shot 04-unfolded 6
tap "Where it went"; shot 05-unfolded-page
adb shell input keyevent KEYCODE_BACK
sleep 2; scroll; shot 04b-unfolded-scrolled
tap "Accounts"; shot 06-unfolded-accounts
tap "Overview"
adb shell wm size reset; adb shell wm density reset
adb shell cmd uimode night yes
shot 07-dark 5
adb shell settings put system font_scale 1.6; shot 07b-large-font 5; adb shell settings put system font_scale 1.0; sleep 3
tap "Settings"; sleep 2; tap "Expense categories"; sleep 2; tap "Dining"; sleep 2; scroll; shot 08-category-colors
adb shell cmd uimode night no
adb shell am force-stop $DPKG
rm -f ci-output/ui.xml
