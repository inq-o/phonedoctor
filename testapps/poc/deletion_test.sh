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
# 어떤 경로로 끝나든(무효 종료 포함) 광고를 멈추고 삭제창을 닫는다
cleanup() { adb shell am force-stop $BGAD >/dev/null 2>&1; adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1; }
trap cleanup EXIT

adb shell am force-stop $BGAD; adb logcat -c
count=$(( secs / interval + 2 ))
adb shell am start -W -n $BGAD/.MainActivity --es mode saw --ei count $count --ei intervalSec $interval --ei holdSec $hold >/dev/null
adb shell input keyevent KEYCODE_HOME
# 전제 1: 광고가 실제로 '재개'됐는지(RESUMED). SHOW만으로는 표시 확인 아님
# 첫 광고는 intervalSec 뒤 예약되므로 그만큼 + 여유 15초를 기다림
for _ in $(seq $((interval + 15))); do adb logcat -d -s BGAD:I | grep -q ' RESUMED ' && break; sleep 1; done
adb logcat -d -s BGAD:I | grep -q ' RESUMED ' || die "광고가 재개되지 않음(알람 빈도 제한 가능)"

# 삭제창 열기
adb shell am start -a android.intent.action.DELETE -d package:$BGAD >/dev/null
# 전제 2: 삭제창(packageinstaller)이 실제로 떴는지
for _ in $(seq 10); do fg | grep -qi "packageinstaller\|uninstall" && break; sleep 1; done
dialog_seen=0; fg | grep -qi "packageinstaller\|uninstall" && dialog_seen=1
[ "$dialog_seen" = 1 ] || die "삭제창이 뜨지 않음(광고가 가로채거나 인텐트 실패)"
dialog_at=$(now)   # 삭제창 뜬 시각 — 이후 광고 반복을 센다

# 삭제창이 떠 있는 샘플만 분모로. 그 중 광고가 포커스를 가진 비율을 기록
echo "at,foreground,dialog_present,ad_focus" > "$out/foreground.csv"
end=$(( $(date +%s) + secs )); dialog_samples=0; covered=0; lost_at=""; fail_samples=0   # lost_at: 떠 있던 삭제창이 사라진 첫 시각
while [ "$(date +%s)" -lt "$end" ]; do
  f=$(fg)
  # 삭제창이 '여전히 열려 있는가'는 창 목록으로 판정(광고가 위를 덮어도 삭제창 창은 존재).
  # 창 목록 수집 자체가 실패하면 '창 없음'으로 세지 않고 수집 실패(NA)로 남김
  wl=$(adb shell dumpsys window windows 2>/dev/null)
  if [ -z "$wl" ]; then d=NA; fail_samples=$((fail_samples+1))
  elif echo "$wl" | grep -qi "packageinstaller"; then d=1; else d=0; fi
  a=0; echo "$f" | grep -q "$BGAD/.*AdActivity" && a=1
  ts=$(now)
  [ "$d" = 1 ] && { dialog_samples=$((dialog_samples+1)); [ "$a" = 1 ] && covered=$((covered+1)); }
  # 삭제창이 한 번 떴다가 창 목록에서 사라지면 '소멸'(덮임과 다름 — 다시 열어야 함)
  [ "$d" = 0 ] && [ "$dialog_samples" -gt 0 ] && [ -z "$lost_at" ] && lost_at=$ts   # NA(수집 실패)는 소멸로 안 셈
  echo "$ts,$f,$d,$a" >> "$out/foreground.csv"
  sleep 1
done

# 관측 중 기기 오류(연결 끊김 등)는 결과와 구분해 오류로 끝냄(exit 1). 창 기록·광고 로그가 빠진 결과를 판정에 쓰지 않음
adb logcat -d -s BGAD:I > "$out/truth.log" 2>/dev/null && [ -s "$out/truth.log" ] || { echo "삭제 시험 오류: 광고 로그 수집 실패(기기 연결 확인)" >&2; exit 1; }
if [ "$fail_samples" -gt 0 ]; then echo "삭제 시험 오류: 관측 중 창 목록 수집 실패 ${fail_samples}회 — 결과 무효(기기 연결 확인)" >&2; exit 1; fi

# 전제 3: 삭제창을 연 뒤 광고가 '반복'됐는지. 삭제창 연 시각(dialog_at) 이후 RESUMED를 센다 (macOS awk 호환: 단순 비교)
res_after=$(grep ' RESUMED mode=' "$out/truth.log" | sed -E 's/.* t=([0-9]+).*/\1/' | awk -v d="$dialog_at" '$1>=d{n++} END{print n+0}')
removed=NA; pkgs=$(adb shell pm list packages 2>/dev/null) && { echo "$pkgs" | grep -q "$BGAD" && removed=설치됨 || removed=지워짐; }
python3 testapps/poc/adgap.py "$out/truth.log" 2>/dev/null | sed -n '2,4p' || true
echo "삭제창 떠 있던 샘플: $dialog_samples (분모) · 광고가 포커스 덮음: $covered"
[ "$fail_samples" -gt 0 ] && echo "창 목록 수집 실패 샘플: $fail_samples (소멸 판정에서 제외)"
if [ -n "$lost_at" ]; then
  # 관측 사실과 해석을 나눔: 사라짐(관측) / 직전 광고 재개(시간상 연관) / 원인(단정 안 함)
  ad_before=$(grep ' RESUMED mode=' "$out/truth.log" | sed -E 's/.* t=([0-9]+).*/\1/' | awk -v l="$lost_at" '$1<=l && l-$1<=3000{n++} END{print n+0}')
  echo "⚠️ 삭제창 사라짐(관측): at=$lost_at. 직전 3초 안 광고 재개 ${ad_before}건(시간상 연관, 원인 단정 아님)"
  echo "   아래 '설치 여부'가 설치됨이면 삭제 성공이 아님. 이 시험엔 사람 조작이 없어 사용자 취소도 아님 → 다시 열어야 하는 상태"
else
  echo "삭제창 사라짐: 없음(관측 끝까지 유지)"
fi
echo "삭제창 연 뒤 광고 재개: ${res_after}회"
echo "bgad 설치 여부: $removed  — 자동 삭제 아님, 시스템 확인창은 사람이 눌러야 함"
# 반복 광고 방해 시험의 전제: 삭제창 샘플 ≥1, 삭제창 이후 재개 ≥2(실제 반복)
if [ "$dialog_samples" -eq 0 ]; then
  echo "⚠️ 무효: 관측 중 삭제창이 떠 있던 샘플 0 — 반복 광고 방해 근거 아님" >&2; exit 2
fi
if [ "${res_after:-0}" -lt 2 ]; then
  echo "⚠️ 반복 광고 미구동(삭제창 이후 재개 <2회). 단일 광고·삭제창 관측 스모크로만 유효, 반복 광고 삭제 검증은 미실시" >&2; exit 2
fi
echo "⚠️ 포커스 샘플 ≠ 시각적 덮임, 기능 시연 ≠ 시니어 완주. 완주는 사용성 관찰로만 확인"
