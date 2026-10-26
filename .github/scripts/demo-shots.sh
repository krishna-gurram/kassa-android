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
# tap_prefix taps the first whose text starts with $1; tap_all taps every on-screen match of $1.
find_xy() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml ci-output/ui.xml > /dev/null 2>&1
  python3 - "$1" "$2" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want, mode = sys.argv[1], sys.argv[2]
try:
    root = ET.parse('ci-output/ui.xml').getroot()
except Exception:
    sys.exit(0)
parent = {c: p for p in root.iter() for c in p}
hits = []
for n in root.iter('node'):
    vals = [v for v in (n.get('text'), n.get('content-desc')) if v]
    ok = any(v.startswith(want) for v in vals) if mode == 'prefix' else want in vals
    if not ok: continue
    m = n
    while m is not None and m.get('clickable') != 'true': m = parent.get(m)
    t = m if m is not None else n
    a, b, c, d = map(int, re.findall(r'\d+', t.get('bounds')))
    if c > a and d > b and (a + c) // 2 >= 0 and (b + d) // 2 >= 0: hits.append(((a + c) // 2, (b + d) // 2, m is not None))
hits.sort(key=lambda h: not h[2])
for x, y, _ in (hits if mode == 'all' else hits[:1]):
    print(x, y)
PY
}
tap_mode() {
  local found=0
  while read -r x y; do [ -n "$x" ] && { adb shell input tap "$x" "$y"; found=1; sleep 0.4; }; done < <(find_xy "$1" "$2")
  [ $found = 1 ] || echo "No \"$1\" on screen"
}
tap() { tap_mode "$1" exact; }
tap_prefix() { tap_mode "$1" prefix; }
tap_all() { tap_mode "$1" all; }

# Cashew import, end to end on a fresh install: a made-up Cashew backup is opened on the first-run Import page
# (debug builds take it from an intent extra instead of the system file picker), every question answered, imported.
FIX=cashew-fixture.sql
python3 .github/scripts/make-cashew-fixture.py /tmp/$FIX > ci-output/cashew-fixture.txt 2>&1
adb push /tmp/$FIX /data/local/tmp/$FIX > /dev/null 2>&1
adb shell "run-as $DPKG mkdir -p files" ; adb shell "run-as $DPKG cp /data/local/tmp/$FIX files/$FIX"
adb shell am start -W -n $ACT --es importFile $FIX > /dev/null 2>&1
shot cashew-1-found 10
tap "Next"; shot cashew-2-accounts
tap_all "Checking"; scroll; tap_all "Checking"; shot cashew-3-accounts-picked
tap "Next"; shot cashew-4-loans
tap_all "A person"; tap "A loan account"; scroll; shot cashew-5-loans-picked
tap "Next"; shot cashew-6-currency
tap "Next"; shot cashew-7-review 4
scroll; shot cashew-8-review-more
scroll; scroll; shot cashew-9-review-end
tap_prefix "Import "; shot cashew-10-done 6
tap "Open Kassa"; shot cashew-11-overview 5
tap "Accounts"; shot cashew-12-accounts
adb logcat -d | grep -A 30 "FATAL EXCEPTION" | grep -A 30 "$DPKG" > ci-output/cashew-crash.txt || true
adb shell am force-stop $DPKG
adb shell pm clear $DPKG > /dev/null 2>&1

adb shell am start -W -n $ACT --ez demo true > /dev/null 2>&1
shot 01-overview 14
scroll
shot 02-overview-scrolled
tap "Accounts"; shot 03-accounts
tap "Overview"
# The Fold's cover screen at the density its screenshots show (about 360 dpi, under 600dp wide so it keeps the bottom bar): Overview should fit.
adb shell wm size 1248x1972; adb shell wm density 360
shot 03b-cover 6
scroll; shot 03c-cover-after-swipe
# Most people have closed the tips: with them gone, Overview should fit with no scrolling.
adb shell input swipe 600 400 600 1600 400; sleep 1
tap "Close tips"; shot 03d-cover-no-tips
scroll; shot 03e-cover-no-tips-after-swipe
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
