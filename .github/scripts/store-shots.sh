#!/usr/bin/env bash
# Google Play screenshots of the debug build with the made-up demo household (USD, no real data).
# Usage: store-shots.sh <prefix> <light|dark> [prep]
#   prefix  file name start, e.g. phone, tablet7, tablet10 (dark pass: phone-dark)
#   prep    first pass on this emulator: wipe, load the demo, add a loan; later passes reuse that data
# PNGs go to store-output/. Each one is checked (PNG header size equals the screen size, and not blank);
# the script goes on after a bad one so every screen is tried, then exits 1 if any failed.
set -u
mkdir -p ci-output
exec > >(tee -a "ci-output/run-${1:-x}-${2:-light}.log") 2>&1
PREFIX=${1:?prefix}
MODE=${2:-light}
PREP=${3:-}
DPKG=app.kassa.money.debug
ACT=$DPKG/app.kassa.money.ui.MainActivity
OUT=store-output
mkdir -p "$OUT" ci-output
FAILED=0

screen_size() { adb shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*'; }
scroll() {
  local wh w h
  wh=$(screen_size); w=${wh%x*}; h=${wh#*x}
  adb shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 4)) 800
}
find_xy() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml ci-output/ui.xml > /dev/null 2>&1
  python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1]
try:
    root = ET.parse('ci-output/ui.xml').getroot()
except Exception:
    sys.exit(0)
parent = {c: p for p in root.iter() for c in p}
hits = []
for n in root.iter('node'):
    if want not in [v for v in (n.get('text'), n.get('content-desc')) if v]: continue
    m = n
    while m is not None and m.get('clickable') != 'true': m = parent.get(m)
    t = m if m is not None else n
    a, b, c, d = map(int, re.findall(r'\d+', t.get('bounds')))
    if c > a and d > b and a >= 0 and b >= 0: hits.append(((a + c) // 2, (b + d) // 2, m is not None))
hits.sort(key=lambda h: not h[2])
for x, y, _ in hits[:1]:
    print(x, y)
PY
}
# Taps the first element whose text or description is exactly $1. Returns 1 when there is none.
tap() {
  local xy
  xy=$(find_xy "$1")
  if [ -z "$xy" ]; then echo "No \"$1\" on screen"; return 1; fi
  # shellcheck disable=SC2086
  adb shell input tap $xy < /dev/null
  sleep 0.8
}
type_into() { tap "$1"; adb shell input text "$2" < /dev/null; sleep 0.6; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 1.5; }

# Takes a screenshot as $OUT/$PREFIX-$1.png and checks it.
shot() {
  local f="$OUT/$PREFIX-$1.png" want got
  sleep "${2:-3}"
  adb exec-out screencap -p > "$f"
  want=$(screen_size)
  if ! python3 - "$f" "$want" <<'PY'
import os, struct, sys
f, want = sys.argv[1], sys.argv[2]
b = open(f, 'rb').read()
if b[:8] != b'\x89PNG\r\n\x1a\n':
    print('not a PNG'); sys.exit(1)
w, h = struct.unpack('>II', b[16:24])
if f'{w}x{h}' != want:
    print(f'size {w}x{h}, expected {want}'); sys.exit(1)
# A blank or black frame (including a FLAG_SECURE capture) compresses to almost nothing.
if os.path.getsize(f) < 25_000:
    print(f'only {os.path.getsize(f)} bytes: blank or black'); sys.exit(1)
PY
  then
    echo "::error::$f failed its check"
    FAILED=1
  fi
}

# One launch of the app, keeping what is stored. noSecure lets screencap see the screen while balances are hidden.
launch() { adb shell am force-stop $DPKG; adb shell am start -W -n $ACT --ez noSecure true "$@" > /dev/null 2>&1; }

adb shell cmd uimode night "$([ "$MODE" = dark ] && echo yes || echo no)"
adb shell settings put system font_scale 1.0

if [ "$PREP" = prep ]; then
  adb shell pm clear $DPKG > /dev/null 2>&1
  launch --ez demo true
  sleep 14
  # A card or loan payment reminder asks for the notification permission after setup; keep it off the screenshots.
  adb shell pm grant $DPKG android.permission.POST_NOTIFICATIONS > /dev/null 2>&1 || true
  # A loan, added the way a person would, then one payment so the page has a schedule.
  tap "Accounts"; sleep 1
  scroll; sleep 1
  tap "Add an account"; sleep 1
  tap "Loan or mortgage"; sleep 1
  adb exec-out screencap -p > ci-output/dbg-1-loan-sheet.png
  type_into "Name" "Car%sloan"
  adb exec-out screencap -p > ci-output/dbg-2-after-name.png
  adb shell uiautomator dump /sdcard/d.xml > /dev/null 2>&1; adb pull /sdcard/d.xml ci-output/dbg-dump.txt > /dev/null 2>&1
  type_into "Amount borrowed" "18000"
  type_into "Interest rate a year" "5.5"
  type_into "Term in months" "48"
  back
  scroll
  tap "Add loan"; sleep 3
  tap "Car loan"; sleep 3
  tap "Record a payment"; sleep 1
  tap "Everyday checking"; sleep 1
  scroll; tap "Record payment"; sleep 2
  adb logcat -d | grep -A 30 "FATAL EXCEPTION" | grep -A 30 "$DPKG" > "ci-output/store-crash-$PREFIX.txt" || true
fi

launch
sleep 10
# Balances must be shown for every screen except the last, whatever an earlier pass left behind.
tap "Show balances" > /dev/null 2>&1 || true
tap "Close tips" > /dev/null 2>&1 || true

shot 01-overview 3
tap "Accounts"; shot 02-accounts 3
tap "Car loan"; sleep 2; scroll; shot 03-loan 2
back
tap "Overview"; sleep 1
tap "Add an entry"; sleep 2
tap "Category"; shot 04-add-category 2
back; back
tap "Activity"; shot 05-activity 3
tap "Insights"; shot 06-insights 4
# Notifications link exists on Overview only when something waits; otherwise the recurring bills list.
tap "Overview"; sleep 1
if tap "Notifications"; then :; else tap "Settings"; sleep 1; tap "Recurring bills"; fi
shot 07-bills 3
back; tap "Overview" > /dev/null 2>&1 || true
tap "Accounts"; sleep 1
if tap "Hide balances"; then shot 08-accounts-hidden 2; else echo "::error::no Hide balances button"; FAILED=1; fi
# Leave balances shown for the next pass.
tap "Show balances" > /dev/null 2>&1 || true

adb logcat -d | grep -A 30 "FATAL EXCEPTION" | grep -A 30 "$DPKG" > "ci-output/store-crash-$PREFIX-$MODE.txt" || true
[ -s "ci-output/store-crash-$PREFIX-$MODE.txt" ] && { echo "::error::the app crashed"; FAILED=1; } || true
n=$(find "$OUT" -name "$PREFIX-0*.png" | wc -l)
[ "$n" -eq 8 ] || { echo "::error::$n of 8 screenshots for $PREFIX"; FAILED=1; }
adb shell cmd uimode night no
adb shell am force-stop $DPKG
rm -f ci-output/ui.xml
exit $FAILED
