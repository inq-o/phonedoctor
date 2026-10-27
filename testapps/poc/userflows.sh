#!/usr/bin/env bash
# ⚠️ 합성 shell 전환 '스모크'. 실제 링크 탭·뒤로가기·로그인/결제 완료가 아니라 `am start`로 화면을 직접 연다.
#    실제 사용자 조작과 호출 주체·태스크·상호작용 이벤트가 같다는 보장이 없으므로 **규칙 개발 자료로 쓰지 말 것**.
#    실제 정상 전환 자료는 UI 자동화로 링크 탭·뒤로가기·인증/결제 완료를 '관찰'하고 성공한 경로만 라벨링하는
#    별도 수집이 필요하다(기기 시험 대상, 시험 계정 사용). 이 스크립트는 파이프라인 연결만 확인한다.
#
# 사용: ANDROID_SERIAL=<기기> testapps/poc/userflows.sh [appA_component] [appB_component] [N=5] [outDir]
set -uo pipefail
cd "$(dirname "$0")/../.."
BGAD=io.github.inq_o.phonedoctor.testapps.bgad
# 기본 A/B는 bgad의 exported 화면(AdActivity는 exported=false라 외부 시작 제한 → NormalActivity 사용)
A=${1:-$BGAD/.NormalActivity}
B=${2:-$BGAD/.NormalActivity}
N=${3:-5}; out=${4:-ml/data/poc/userflows_smoke}; mkdir -p "$out"
PROBE=io.github.inq_o.phonedoctor.testapps.usageprobe
truth="$out/windows.csv"; echo "label,i,start,end" > "$truth"

die() { echo "userflows 실패: $1" >&2; exit 1; }
[ "$(adb get-state 2>/dev/null)" = "device" ] || die "연결된 기기 없음. ANDROID_SERIAL로 기기를 고정하세요"
now() { echo $(( $(adb shell date +%s%N | tr -d '\r') / 1000000 )); }
home() { adb shell input keyevent KEYCODE_HOME >/dev/null; sleep 1; }
# am start 성공(Status: ok)을 확인하고, 실패하면 그 구간을 라벨하지 않음
start_ok() { adb shell am start -W "$@" 2>&1 | grep -q 'Status: ok'; }
win() { echo "$1,$2,$3,$(now)" >> "$truth"; }

begin=$(now)
for i in $(seq 1 "$N"); do
  # smoke_link: A를 열었다가 B 화면을 am start로 엶 (실제 링크 탭 아님)
  home; start_ok -S -n $A || { echo "i=$i A 시작 실패, 건너뜀" >&2; continue; }; sleep 2
  s=$(now); start_ok -n $B && sleep 3 && win smoke_link $i $s || echo "i=$i B 시작 실패, link 미기록" >&2
  # smoke_return: A→B→A 복귀(am start, 실제 뒤로가기 아님)
  home; start_ok -S -n $A || continue; sleep 2; start_ok -n $B && sleep 2 || true
  s=$(now); start_ok -n $A && sleep 3 && win smoke_return $i $s || echo "i=$i return 미기록" >&2
done
end=$(now)

adb shell am broadcast -f 32 -n $PROBE/.DumpReceiver --el from $begin --el to $end --es out userflows_smoke.csv >/dev/null; sleep 2
adb exec-out run-as $PROBE cat files/userflows_smoke.csv > "$out/events.csv" 2>/dev/null || die "이벤트 덤프 실패"
echo "userflows(합성 스모크) 완료: $out/windows.csv $out/events.csv"
echo "⚠️ 이 자료는 파이프라인 확인용. 규칙 개발·평가 자료로 쓰지 말 것 — 실제 UI 흐름 관찰 수집이 따로 필요"
