#!/usr/bin/env bash
# 실증 공통 준비: 두 테스트 앱 설치·권한. 개발용 AVD(Pixel_8_API_35)에서만 (T026 관찰용 Observe_API_35와 섞지 않음)
set -euo pipefail
cd "$(dirname "$0")/../.."
BGAD=io.github.inq_o.phonedoctor.testapps.bgad
PROBE=io.github.inq_o.phonedoctor.testapps.usageprobe
adb install -r -g testapps/bgad/build/outputs/apk/debug/bgad-debug.apk
adb install -r testapps/usageprobe/build/outputs/apk/debug/usageprobe-debug.apk
adb shell appops set $BGAD SYSTEM_ALERT_WINDOW allow
adb shell appops set $BGAD USE_FULL_SCREEN_INTENT allow
adb shell appops set $PROBE GET_USAGE_STATS allow
adb shell svc power stayon true
adb shell locksettings set-disabled false   # 스와이프 잠금: 전체 화면 알림·잠금화면 동작을 실제 폰처럼
adb shell cmd alarm set-timezone Asia/Seoul
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell input keyevent KEYCODE_HOME
