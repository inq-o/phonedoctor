#!/usr/bin/env bash
# E4 지연·실패 조건: 조건 하나를 걸고 bgad 방식 ①을 주기적으로 띄우며, usageprobe 15분 주기 작업이 언제 처음 보는지 기록
# 사용: testapps/poc/e4.sh <normal|screen_off|doze|rare|killed|noperm> [minutes=40] [intervalSec=240] [outDir]
set -uo pipefail
cond=$1; minutes=${2:-40}; interval=${3:-240}
out=${4:-ml/data/poc/e4}/$cond; mkdir -p "$out"
BGAD=io.github.inq_o.phonedoctor.testapps.bgad
PROBE=io.github.inq_o.phonedoctor.testapps.usageprobe
sh() { adb shell "$@" >/dev/null 2>&1; }
now() { echo $(( $(adb shell date +%s%N | tr -d '\r') / 1000000 )); }
wake() { sh input keyevent KEYCODE_WAKEUP; sleep 1; sh wm dismiss-keyguard; }

sh am force-stop $PROBE
sh am force-stop $BGAD   # 남은 알람·얼어 있는 프로세스 제거
sh run-as $PROBE rm -f files/work.csv files/runs.csv shared_prefs/cursor.xml
sh appops set $PROBE GET_USAGE_STATS allow
sh am set-standby-bucket $PROBE active   # 화면 없는 앱은 NEVER(50) 버킷이라 주기 작업 할당량이 0. 설정 직후 한 번 열린 앱을 가정
sh input keyevent KEYCODE_HOME
adb logcat -c
begin=$(now)
sh am broadcast -f 32 -n $PROBE/.WorkReceiver --es action start
sleep 5
case $cond in
  normal) ;;
  screen_off) sh svc power stayon false; sh input keyevent KEYCODE_SLEEP ;;
  doze) sh svc power stayon false; sh dumpsys battery unplug; sh input keyevent KEYCODE_SLEEP; sleep 5; sh dumpsys deviceidle force-idle ;;
  rare) sh am set-standby-bucket $PROBE rare ;;
  killed) sh am force-stop $PROBE ;;   # 사용자가 "강제 종료"한 상태: WorkManager 작업도 취소됨
  noperm) sh appops set $PROBE GET_USAGE_STATS deny ;;
esac
count=$(( minutes * 60 / interval ))
sh am start -W -n $BGAD/.MainActivity --es mode saw --ei count $count --ei intervalSec $interval --ei holdSec 3
# 실행 직후 HOME을 누르면 onCreate 전에 묻혀 알람이 안 걸릴 수 있어 확인 후 진행
for _ in 1 2 3 4 5 6 7 8 9 10; do adb logcat -d -s BGAD:I | grep -q "seq=0 inSec=$interval left=$count" && break; sleep 1; done
adb logcat -d -s BGAD:I | grep -q "seq=0 inSec=$interval left=$count" || { echo "$cond: bgad 알람 등록 실패" >&2; exit 1; }
sh input keyevent KEYCODE_HOME
[ "$cond" = screen_off ] || [ "$cond" = doze ] && sh input keyevent KEYCODE_SLEEP
sleep $(( minutes * 60 + 30 ))
end=$(now)
# 원상 복구
case $cond in
  screen_off) wake; sh svc power stayon true ;;
  doze) sh dumpsys deviceidle unforce; sh dumpsys battery reset; wake; sh svc power stayon true ;;
  rare) sh am set-standby-bucket $PROBE active ;;
  noperm) sh appops set $PROBE GET_USAGE_STATS allow ;;
esac
adb logcat -d -s BGAD:I USAGEPROBE:I WM-WorkerWrapper:I > "$out/logcat.txt"
grep " SHOW " "$out/logcat.txt" > "$out/truth.log" || true
adb exec-out run-as $PROBE cat files/work.csv > "$out/work.csv" 2>/dev/null
adb exec-out run-as $PROBE cat files/runs.csv > "$out/runs.csv" 2>/dev/null
sh am broadcast -f 32 -n $PROBE/.DumpReceiver --el from $begin --el to $end --es out e4_$cond.csv; sleep 2
adb exec-out run-as $PROBE cat files/e4_$cond.csv > "$out/events.csv"
sh am broadcast -f 32 -n $PROBE/.WorkReceiver --es action stop
echo "$cond: SHOW=$(wc -l < "$out/truth.log") runs=$(wc -l < "$out/runs.csv" 2>/dev/null || echo 0) begin=$begin end=$end"
