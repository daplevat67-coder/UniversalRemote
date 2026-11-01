#!/usr/bin/env bash
set -euo pipefail
SDKMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/avdmanager"
"$SDKMANAGER" "emulator" "system-images;android-35;google_apis;x86_64"
echo no | "$AVDMANAGER" create avd --force --name ur-ui --package "system-images;android-35;google_apis;x86_64"
sudo chmod a+rw /dev/kvm
"${ANDROID_HOME}/emulator/emulator" -avd ur-ui -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect > "$RUNNER_TEMP/emulator.log" 2>&1 &
emulator_pid=$!
trap 'adb emu kill >/dev/null 2>&1 || kill "$emulator_pid" || true' EXIT
timeout 180 adb wait-for-device
for i in $(seq 1 120); do
  if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]; then break; fi
  sleep 2
done
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1"
adb shell input keyevent 82
adb shell wm size 1080x2400
adb shell wm density 420
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r companion/build/outputs/apk/debug/companion-debug.apk
app_package="${ANDROID_APPLICATION_ID:-com.example.universalremote}"
companion_package="${ANDROID_COMPANION_APPLICATION_ID:-com.example.universalremote.companion}"
for permission in ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION BLUETOOTH_SCAN BLUETOOTH_CONNECT NEARBY_WIFI_DEVICES; do
  adb shell pm grant "$app_package" "android.permission.$permission"
done
mkdir -p dist/ui-preview
adb shell am start -W -n "$app_package/com.example.universalremote.DiscoveryPermissionActivity"
sleep 2
adb shell uiautomator dump /sdcard/window.xml >/dev/null
adb pull /sdcard/window.xml dist/ui-preview/home.xml
adb exec-out screencap -p > dist/ui-preview/home.png
python3 - <<'PY'
import re, subprocess, xml.etree.ElementTree as ET
root = ET.parse('dist/ui-preview/home.xml').getroot()
assert any('Устройства рядом' in n.get('text', '') for n in root.iter('node')), 'Main screen did not open'
gear = next(n for n in root.iter('node') if n.get('content-desc') == 'Настройки')
x1, y1, x2, y2 = map(int, re.findall(r'\d+', gear.attrib['bounds']))
subprocess.run(['adb', 'shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)], check=True)
PY
sleep 1
adb exec-out screencap -p > dist/ui-preview/settings.png
adb shell am start -W -n "$companion_package/com.example.universalremote.companion.CompanionMainActivity"
sleep 2
adb exec-out screencap -p > dist/ui-preview/companion.png
adb logcat -d -b crash > dist/ui-preview/crash.log
if grep -q 'FATAL EXCEPTION' dist/ui-preview/crash.log; then cat dist/ui-preview/crash.log; exit 1; fi
