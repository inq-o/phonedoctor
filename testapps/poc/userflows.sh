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
# 사용 기록 권한이 없으면 덤프가 비어 '정상 관측'과 구분이 안 됨 → 먼저 확인
adb shell appops get $PROBE GET_USAGE_STATS 2>/dev/null | grep -q 'allow' || die "usageprobe 사용 기록 권한 없음(appops GET_USAGE_STATS)"

begin=$(now)
for i in $(seq 1 "$N"); do
  # smoke_link: A를 열었다가 B 화면을 am start로 엶 (실제 링크 탭 아님)
  home; start_ok -S -n $A || { echo "i=$i A 시작 실패, 건너뜀" >&2; continue; }; sleep 2
  s=$(now); start_ok -n $B && sleep 3 && win smoke_link $i $s || echo "i=$i B 시작 실패, link 미기록" >&2
  # smoke_return: A→B→A 복귀(am start, 실제 뒤로가기 아님)
  home; start_ok -S -n $A || continue; sleep 2
  start_ok -n $B || { echo "i=$i B 시작 실패, return 미기록" >&2; continue; }; sleep 2   # B 실패 시 복귀 구간 자체가 성립 안 함
  s=$(now); start_ok -n $A && sleep 3 && win smoke_return $i $s || echo "i=$i return 미기록" >&2
done
end=$(now)

valid=$(( $(wc -l < "$truth") - 1 ))
[ "$valid" -gt 0 ] || die "유효 구간 0개(모든 시작 실패 또는 N=0)"
# 이전 실행의 덤프가 섞이지 않게 먼저 지우고(adb shell은 원격 종료 코드를 돌려줌), 새로 만든 뒤 읽는다
adb shell run-as $PROBE rm -f files/userflows_smoke.csv || die "이전 덤프 삭제 실패"
adb shell am broadcast -f 32 -n $PROBE/.DumpReceiver --el from $begin --el to $end --es out userflows_smoke.csv >/dev/null; sleep 2
# adb exec-out은 원격 실패도 exit 0 → 원격 종료 코드를 sentinel로 받음
o=$(adb exec-out run-as $PROBE sh -c "cat files/userflows_smoke.csv; echo __RC:\$?")
[ "$(printf '%s\n' "$o" | tail -1)" = "__RC:0" ] || die "이벤트 덤프 읽기 실패(파일 미생성·접근 불가)"
rm -f "$out/events.csv"   # 이전 결과가 남아 아래 형식 확인을 통과하지 않게
printf '%s\n' "$o" | sed '$d' > "$out/events.csv" || die "events.csv 쓰기 실패"
# 형식 확인: DumpReceiver 헤더로 시작하고, 기록한 구간이 있으니 이벤트 행이 1개 이상이어야 함
head -1 "$out/events.csv" | grep -q '^queriedAt,timeStamp,type' || die "events.csv 형식 오류(헤더 없음)"
[ "$(wc -l < "$out/events.csv")" -gt 1 ] || die "이벤트 0건(권한·수신기 확인) — 헤더만 있는 덤프를 결과로 쓰지 않음"
echo "userflows(합성 스모크) 완료: 유효 구간 ${valid}개 — $out/windows.csv $out/events.csv"
echo "⚠️ 이 자료는 파이프라인 확인용. 규칙 개발·평가 자료로 쓰지 말 것 — 실제 UI 흐름 관찰 수집이 따로 필요"
