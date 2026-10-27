#!/usr/bin/env bash
# 정상 사용자 앱 전환 개발 자료: 사용자 설치 앱 사이의 링크·복귀·로그인/결제 복귀를 모은다.
# ⚠️ 규칙 '개발·튜닝' 자료용. 최종 평가 자료와 분리한다(ml/data/poc/userflows_dev). 같은 자료로 규칙을 만들고 평가하지 않음.
# E2(시스템 앱 위주)의 공백인 사용자 앱↔사용자 앱 전환을 채운다.
#
# 두 사용자 앱의 컴포넌트를 받는다. 지정 안 하면 bgad의 액티비티로 스모크 테스트(실제 평가엔 진짜 앱으로 교체).
# 사용: testapps/poc/userflows.sh [appA_component] [appB_component] [N=5] [outDir]
set -uo pipefail
cd "$(dirname "$0")/../.."
BGAD=io.github.inq_o.phonedoctor.testapps.bgad
A=${1:-$BGAD/.NormalActivity}          # 사용자 앱 A의 한 화면(런처 아님)
B=${2:-$BGAD/.AdActivity}              # 사용자 앱 B의 한 화면(링크로 열릴 상세 등)
N=${3:-5}; out=${4:-ml/data/poc/userflows_dev}; mkdir -p "$out"
PROBE=io.github.inq_o.phonedoctor.testapps.usageprobe
truth="$out/windows.csv"; echo "label,i,start,end" > "$truth"
now() { echo $(( $(adb shell date +%s%N | tr -d '\r') / 1000000 )); }
sh() { adb shell "$@" >/dev/null 2>&1; }
home() { sh input keyevent KEYCODE_HOME; sleep 1; }
win() { echo "$1,$2,$3,$(now)" >> "$truth"; }

begin=$(now)
for i in $(seq 1 "$N"); do
  # 1 link: A를 쓰다가 '링크'로 B의 상세 화면을 엶 (사용자가 A에서 눌렀지만 대상은 B)
  home; sh am start -S -n $A; sleep 2
  s=$(now); sh am start -n $B --es mode link --ei seq 0 --ei holdSec 3; sleep 4; win link $i $s
  # 2 return: A 화면 → B 갔다가 → A로 복귀 (B의 USER_INTERACTION 없이 이전 앱 상세로 돌아옴)
  home; sh am start -S -n $A; sleep 2; sh am start -n $B --es mode link --ei seq 0 --ei holdSec 2; sleep 3
  s=$(now); sh am start -n $A; sleep 3; win return $i $s
  # 3 login_return: A에서 로그인/결제 화면(B)으로 갔다가 완료 후 A로 (복귀가 정상)
  home; sh am start -S -n $A; sleep 2; sh am start -n $B --es mode login --ei seq 0 --ei holdSec 2; sleep 3
  s=$(now); sh am start -n $A; sleep 3; win login_return $i $s
done
end=$(now)

sh am broadcast -f 32 -n $PROBE/.DumpReceiver --el from $begin --el to $end --es out userflows_dev.csv; sleep 2
adb exec-out run-as $PROBE cat files/userflows_dev.csv > "$out/events.csv"
echo "userflows(dev) 완료: $out/windows.csv $out/events.csv"
echo "rule_v0로 개발 확인(평가 아님): python3 testapps/poc/rule_v0.py <launchers.csv> <e1 truth> <e1 events> $out/windows.csv $out/events.csv"
echo "⚠️ 기본값은 bgad 스모크. 실제 개발 자료는 사용자 앱 컴포넌트로 교체"
