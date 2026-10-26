#!/usr/bin/env bash
# E1 보이는가: bgad 방식 하나를 count번 띄우고, 그 구간의 queryEvents와 bgad 정답 로그를 남긴다
# 화면 꺼진 상태: SCREEN_OFF=1 testapps/poc/e1.sh ...
# 사용: testapps/poc/e1.sh <saw|fsi|overlay> [count=10] [intervalSec=20] [holdSec=3] [outDir]
set -euo pipefail
mode=$1; count=${2:-10}; interval=${3:-20}; hold=${4:-3}
out=${5:-ml/data/poc/e1}; mkdir -p "$out"
BGAD=io.github.inq_o.phonedoctor.testapps.bgad
PROBE=io.github.inq_o.phonedoctor.testapps.usageprobe
adb shell input keyevent KEYCODE_HOME
adb logcat -c
if [ "${SCREEN_OFF:-0}" = 1 ]; then adb shell svc power stayon false; adb shell input keyevent KEYCODE_SLEEP; sleep 3; fi  # 화면 꺼진 상태에서 띄우기
from=$(( $(adb shell date +%s | tr -d '\r') * 1000 ))
adb shell am start -n $BGAD/.MainActivity --es mode "$mode" --ei count "$count" --ei intervalSec "$interval" --ei holdSec "$hold" >/dev/null
sleep $(( count * interval + hold + 10 ))
to=$(( $(adb shell date +%s | tr -d '\r') * 1000 ))
if [ "${SCREEN_OFF:-0}" = 1 ]; then adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; adb shell svc power stayon true; fi
adb logcat -d -s BGAD:I > "$out/$mode.truth.log"
adb shell am broadcast -n $PROBE/.DumpReceiver --el from $from --el to $to --es out "e1_$mode.csv" >/dev/null
sleep 2
adb exec-out run-as $PROBE cat "files/e1_$mode.csv" > "$out/$mode.events.csv"
adb exec-out screencap -p > "$out/$mode.last.png" || true
echo "$mode: SHOW=$(grep -c ' SHOW ' "$out/$mode.truth.log") RESUMED=$(grep -c ' RESUMED ' "$out/$mode.truth.log") events=$(( $(wc -l < "$out/$mode.events.csv") - 1 ))"
