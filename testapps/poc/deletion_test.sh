#!/usr/bin/env bash
# 반복 광고 중 삭제 흐름 시험. bgad가 광고를 띄우는 동안 시스템 삭제창을 열고, 삭제창이 떠 있는 동안
# 광고가 그 위를 덮는지(포그라운드 창)를 기록한다.
# 전제(광고 실제 재개·삭제창 표시)를 검증하고, 못 채우면 "시험 무효"로 끝낸다.
# ⚠️ 이 도구는 "광고가 삭제창을 덮는가"의 증거일 뿐, 시니어 실제 삭제 완주를 입증하지 않는다(사용성 관찰 별도).
# 사용: ANDROID_SERIAL=<기기> testapps/poc/deletion_test.sh [intervalSec=5] [holdSec=3] [seconds=60] [outDir]
set -uo pipefail
cd "$(dirname "$0")/../.."
BGAD=io.github.inq_o.phonedoctor.testapps.bgad
interval=${1:-5}; hold=${2:-3}; secs=${3:-60}; out=${4:-ml/data/poc/deletion}; mkdir -p "$out"

die() { echo "삭제 시험 무효: $1" >&2; exit 2; }   # exit 2 = 전제 미충족(무효), 1 = 오류
[ "$(adb get-state 2>/dev/null)" = "device" ] || die "연결된 기기 없음"
now() { echo $(( $(adb shell date +%s%N | tr -d '\r') / 1000000 )); }
fg() { adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | sed -E 's/.*u0 //; s/}.*//' | tr -d '\r'; }

adb shell am force-stop $BGAD; adb logcat -c
count=$(( secs / interval + 2 ))
adb shell am start -W -n $BGAD/.MainActivity --es mode saw --ei count $count --ei intervalSec $interval --ei holdSec $hold >/dev/null
adb shell input keyevent KEYCODE_HOME
# 전제 1: 광고가 실제로 '재개'됐는지(RESUMED). SHOW만으로는 표시 확인 아님
for _ in $(seq 15); do adb logcat -d -s BGAD:I | grep -q ' RESUMED ' && break; sleep 1; done
adb logcat -d -s BGAD:I | grep -q ' RESUMED ' || die "광고가 재개되지 않음(알람 빈도 제한 가능)"

# 삭제창 열기
adb shell am start -a android.intent.action.DELETE -d package:$BGAD >/dev/null
# 전제 2: 삭제창(packageinstaller)이 실제로 떴는지
for _ in $(seq 10); do fg | grep -qi "packageinstaller\|uninstall" && break; sleep 1; done
dialog_seen=0; fg | grep -qi "packageinstaller\|uninstall" && dialog_seen=1
[ "$dialog_seen" = 1 ] || die "삭제창이 뜨지 않음(광고가 가로채거나 인텐트 실패)"

# 삭제창이 떠 있는 샘플만 분모로. 그 중 광고가 포커스를 가진 비율을 기록
echo "at,foreground,dialog_present,ad_focus" > "$out/foreground.csv"
end=$(( $(date +%s) + secs )); dialog_samples=0; covered=0
while [ "$(date +%s)" -lt "$end" ]; do
  f=$(fg)
  d=0; echo "$f" | grep -qi "packageinstaller\|uninstall\|$BGAD/.AdActivity" && :  # 포커스가 삭제창 또는 광고
  # 삭제창이 '여전히 열려 있는가'는 창 목록으로 판정(광고가 위를 덮어도 삭제창 창은 존재)
  adb shell dumpsys window windows 2>/dev/null | grep -qi "packageinstaller" && d=1
  a=0; echo "$f" | grep -q "$BGAD/.*AdActivity" && a=1
  [ "$d" = 1 ] && { dialog_samples=$((dialog_samples+1)); [ "$a" = 1 ] && covered=$((covered+1)); }
  echo "$(now),$f,$d,$a" >> "$out/foreground.csv"
  sleep 1
done

adb logcat -d -s BGAD:I > "$out/truth.log"
removed=NA; pkgs=$(adb shell pm list packages 2>/dev/null) && { echo "$pkgs" | grep -q "$BGAD" && removed=설치됨 || removed=지워짐; }
python3 testapps/poc/adgap.py "$out/truth.log" 2>/dev/null | sed -n '2,4p' || true
adb shell am force-stop $BGAD
echo "삭제창이 떠 있던 샘플: $dialog_samples / (위 분모로만 판정)"
echo "그 중 광고가 포커스를 덮은 샘플: $covered"
echo "bgad 설치 여부: $removed  — 자동 삭제 아님, 시스템 확인창은 사람이 눌러야 함"
echo "⚠️ 포커스 샘플 ≠ 시각적 덮임, 기능 시연 ≠ 시니어 완주. 완주는 사용성 관찰로만 확인"
