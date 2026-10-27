#!/usr/bin/env bash
# 반복 광고 중 삭제 흐름 시험. bgad가 짧은 간격으로 광고를 띄우는 동안 시스템 삭제창을 열고,
# 광고가 그 위를 덮는지(포그라운드 창)를 주기적으로 기록한다.
# ⚠️ 이 도구는 "광고가 삭제창을 덮는가"의 증거를 남길 뿐, 시니어의 실제 삭제 완주를 입증하지 않는다(사용성 관찰 별도).
# 사용: testapps/poc/deletion_test.sh [intervalSec=5] [holdSec=3] [seconds=60] [outDir=ml/data/poc/deletion]
set -uo pipefail
cd "$(dirname "$0")/../.."
BGAD=io.github.inq_o.phonedoctor.testapps.bgad
interval=${1:-5}; hold=${2:-3}; secs=${3:-60}; out=${4:-ml/data/poc/deletion}; mkdir -p "$out"
now() { echo $(( $(adb shell date +%s%N | tr -d '\r') / 1000000 )); }
fg() { adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | sed -E 's/.*u0 //; s/}.*//' | tr -d '\r'; }

adb shell am force-stop $BGAD
adb logcat -c
count=$(( secs / interval + 2 ))
# 광고 반복 시작
adb shell am start -W -n $BGAD/.MainActivity --es mode saw --ei count $count --ei intervalSec $interval --ei holdSec $hold >/dev/null
adb shell input keyevent KEYCODE_HOME
# 첫 광고가 뜬 뒤 삭제창 열기
for _ in $(seq 10); do adb logcat -d -s BGAD:I | grep -q ' SHOW ' && break; sleep 1; done
adb shell am start -a android.intent.action.DELETE -d package:$BGAD >/dev/null

# 포그라운드 창을 초당 기록: 삭제창(packageinstaller)인지 광고(bgad)인지
echo "at,foreground" > "$out/foreground.csv"
end=$(( $(date +%s) + secs ))
covered=0; total=0
while [ "$(date +%s)" -lt "$end" ]; do
  f=$(fg); total=$((total+1))
  echo "$(now),$f" >> "$out/foreground.csv"
  echo "$f" | grep -q "$BGAD/.*AdActivity" && covered=$((covered+1))
  sleep 1
done

adb logcat -d -s BGAD:I > "$out/truth.log"
removed=$(adb shell pm list packages | grep -c "$BGAD")  # 0이면 지워짐(수동 확인 시)
python3 testapps/poc/adgap.py "$out/truth.log" 2>/dev/null | sed -n '2,3p' > "$out/gap.txt" || true
adb shell am force-stop $BGAD
cat "$out/gap.txt" 2>/dev/null
echo "삭제창 떠 있는 동안 광고가 덮은 샘플: $covered/$total (초 단위)"
echo "bgad 설치 여부(0=지워짐): $removed  — 자동 삭제 아님, 시스템 확인창은 사람이 눌러야 함"
echo "⚠️ 이 결과는 덮임 증거일 뿐, 시니어 완주 가능성은 사용성 관찰로만 확인"
