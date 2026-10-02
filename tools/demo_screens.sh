#!/usr/bin/env bash
# Installs the prodDebug APK on the running emulator, opens the app and saves screenshots plus the app's log to demo/.
set -u
mkdir -p demo
adb install -r app/build/outputs/apk/prod/debug/app-prod-debug.apk
adb logcat -c
adb shell am start -W -n dev.agentle.app/.MainActivity
sleep 15
shot() { adb exec-out screencap -p > "demo/$1.png"; }
shot 01-launch
# Walk forward through the first screens with the primary button area and the back stack, capturing each.
for i in 2 3 4 5 6; do
  adb shell input keyevent KEYCODE_TAB; adb shell input keyevent KEYCODE_ENTER; sleep 4
  shot "0$i-step"
done
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml demo/last-screen.xml
adb logcat -d -v time > demo/logcat.txt
grep -E "FATAL EXCEPTION|AndroidRuntime" -A20 demo/logcat.txt > demo/crashes.txt || true
adb shell pidof dev.agentle.app > demo/pid.txt || echo "not running" > demo/pid.txt
exit 0
