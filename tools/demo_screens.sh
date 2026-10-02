#!/usr/bin/env bash
# Installs the prodDebug APK on the running emulator, opens the app, visits a few screens by tapping their labels and
# saves screenshots, the last UI tree and the app's log to demo/.
set -u
mkdir -p demo
adb install -r app/build/outputs/apk/prod/debug/app-prod-debug.apk
adb logcat -c
adb shell am start -W -n dev.agentle.app/.MainActivity
sleep 15
n=1
shot() { adb exec-out screencap -p > "demo/$(printf '%02d' $n)-$1.png"; n=$((n + 1)); }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb shell cat /sdcard/ui.xml; }
# Taps the first node whose text or content description contains $1 (case-insensitive); returns 1 if none.
tap() {
  local xy
  xy=$(dump | python3 -c '
import re, sys
xml, want = sys.stdin.read(), sys.argv[1].lower()
for m in re.finditer(r"<node [^>]*>", xml):
    node = m.group(0)
    label = " ".join(re.findall(r"(?:text|content-desc)=\"([^\"]*)\"", node)).lower()
    if want in label:
        x1, y1, x2, y2 = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$1")
  [ -n "$xy" ] || return 1
  adb shell input tap $xy
  sleep 4
}
shot dashboard
dump > demo/dashboard.xml
for screen in "Timeline" "Data sources" "Permission" "Insights" "Settings"; do
  if tap "$screen"; then
    shot "$(echo "$screen" | tr ' A-Z' '-a-z')"
    adb shell input keyevent KEYCODE_BACK
    sleep 3
  else
    echo "no '$screen' on the dashboard" >> demo/notes.txt
  fi
done
adb logcat -d -v time > demo/logcat.txt
grep -E "FATAL EXCEPTION" -A30 demo/logcat.txt > demo/crashes.txt || true
adb shell pidof dev.agentle.app > demo/pid.txt || echo "not running" > demo/pid.txt
exit 0
