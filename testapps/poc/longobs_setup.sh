#!/usr/bin/env bash
# 장기 관측 1회 초기 설정. 설치 → 권한 → 시작 화면 한 번 열기 → 주기 작업 시작.
# 이후 관측은 longobs_collect.sh로만 하고, 이 스크립트를 다시 돌리지 않는다(상태 초기화됨).
# 사용: testapps/poc/longobs_setup.sh [outDir=ml/data/poc/longobs]
set -uo pipefail
cd "$(dirname "$0")/../.."
PROBE=io.github.inq_o.phonedoctor.testapps.usageprobe
BGAD=io.github.inq_o.phonedoctor.testapps.bgad
out=${1:-ml/data/poc/longobs}; mkdir -p "$out"
now() { echo $(( $(adb shell date +%s%N | tr -d '\r') / 1000000 )); }

adb install -r testapps/usageprobe/build/outputs/apk/debug/usageprobe-debug.apk
adb install -r -g testapps/bgad/build/outputs/apk/debug/bgad-debug.apk
adb shell appops set $PROBE GET_USAGE_STATS allow
# 실제 온보딩처럼 앱을 한 번 연다(대기 버킷을 NEVER에서 올림). 이후 다시 열지 않음
adb shell am start -W -n $PROBE/.MainActivity >/dev/null
adb shell input keyevent KEYCODE_HOME
adb shell am broadcast -f 32 -n $PROBE/.WorkReceiver --es action start >/dev/null
printf 'event=setup\nat=%s\nbucket=%s\n' "$(now)" "$(adb shell am get-standby-bucket $PROBE | tr -d '\r')" >> "$out/log.txt"
echo "setup 완료: $out/log.txt. 이후 손대지 말고 longobs_collect.sh만 사용"
