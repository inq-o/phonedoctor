#!/usr/bin/env bash
# E2 정상 전환 수집: 사용자가 일으킨(또는 정상 시스템) 전환 8종 x N회. 구간마다 라벨·시작·끝(기기 시각 ms)을 남기고
# 끝에 전체 구간 queryEvents를 뽑는다. 좌표는 Pixel_8_API_35 기본 홈 화면 기준
# 사용: testapps/poc/e2.sh [N=10] [outDir]
set -uo pipefail
N=${1:-10}; out=${2:-ml/data/poc/e2}; mkdir -p "$out"
BGAD=io.github.inq_o.phonedoctor.testapps.bgad
PROBE=io.github.inq_o.phonedoctor.testapps.usageprobe
truth="$out/windows.csv"; echo "label,i,start,end" > "$truth"
now() { echo $(( $(adb shell date +%s%N | tr -d '\r') / 1000000 )); }
sh() { adb shell "$@" >/dev/null 2>&1; }
home() { sh input keyevent KEYCODE_HOME; sleep 1; }
tap_text() {  # 화면에서 text가 정확히 $1인 노드 가운데를 누름
  local xy
  xy=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null | python3 -c '
import re,sys
t=sys.argv[1]; x=sys.stdin.read()
m=re.search(r"text=\""+re.escape(t)+r"\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",x)
print(f"{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}" if m else "")' "$1")
  [ -n "$xy" ] && sh input tap $xy
}
win() { echo "$1,$2,$3,$(now)" >> "$truth"; }
wake() { sh input keyevent KEYCODE_WAKEUP; sleep 1; sh wm dismiss-keyguard; sleep 1; }

begin=$(now)
for i in $(seq 1 "$N"); do
  # 1 런처 아이콘 탭 (전화·메시지 번갈아)
  home; s=$(now); if (( i % 2 )); then sh input tap 169 1970; else sh input tap 416 1970; fi; sleep 3; win launcher_tap $i $s
  # 2 알림 탭 → 앱 열림
  sh am start -S -n $BGAD/.MainActivity --es mode notif --ei count 1 --ei intervalSec 1; sleep 4; home  # -S: 남은 bgad 화면 정리
  s=$(now); sh cmd statusbar expand-notifications; sleep 1.5; tap_text "메시지 0"; sleep 3; win notif_tap $i $s
  # 3 최근 앱 화면에서 전환
  home; sh input tap 169 1970; sleep 2; home; s=$(now); sh input keyevent KEYCODE_APP_SWITCH; sleep 1.5; sh input tap 540 1200; sleep 3; win recents $i $s
  # 4 앱 안에서 사용자가 눌러 다른 앱 열기 (bgad 정상 화면 → 설정)
  home; sh am start -S -n $BGAD/.NormalActivity; sleep 2; s=$(now); tap_text "설정 열기"; sleep 3; win cross_app $i $s
  # 5 화면 끄고 켜면 보던 앱 복귀
  home; sh input tap 169 1970; sleep 2; s=$(now); sh input keyevent KEYCODE_SLEEP; sleep 3; wake; sleep 2; win screen_on $i $s
  # 6 타이머 울림 (화면 꺼진 상태 → 전체 화면)
  home; sh am start -a android.intent.action.SET_TIMER --ei android.intent.extra.alarm.LENGTH 6 --ez android.intent.extra.alarm.SKIP_UI true; sleep 1; home
  s=$(now); sh input keyevent KEYCODE_SLEEP; sleep 12; sh am force-stop com.google.android.deskclock; wake; win timer $i $s
  # 7 전화 수신 (화면 꺼진 상태)
  home; s=$(now); sh input keyevent KEYCODE_SLEEP; sleep 2; adb emu gsm call 01012345678 >/dev/null; sleep 6; adb emu gsm cancel 01012345678 >/dev/null; sleep 2; wake; win incoming_call $i $s
  # 8 앱 안 전면 광고 (R6, 앱 밖 광고 아님)
  home; sh am start -S -n $BGAD/.NormalActivity; sleep 2; s=$(now); tap_text "앱 안 광고"; sleep 5; win inapp_ad $i $s
  echo "round $i done"
done
home; end=$(now)
sh am broadcast -n $PROBE/.DumpReceiver --el from $begin --el to $end --es out e2.csv; sleep 3
adb exec-out run-as $PROBE cat files/e2.csv > "$out/events.csv"
echo "windows=$(( $(wc -l < "$truth") - 1 )) events=$(( $(wc -l < "$out/events.csv") - 1 ))"
