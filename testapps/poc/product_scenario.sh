#!/usr/bin/env bash
# 정상 사용 시나리오 기록. 폰에서 사람이 동작을 하나씩 하고, 동작마다 시각과 검사 결과를 남긴다.
# 모든 동작이 정상 사용이므로, 어느 단계에서든 광고로 센 전환은 오탐 후보다.
# 사용: ANDROID_SERIAL=<기기> testapps/poc/product_scenario.sh [outDir]
#   각 단계에서 Enter=시작/끝, s=건너뜀, q=여기서 끝냄. 끝낼 때 글자를 쓰면 메모로 남는다.
# 전제: 폰주치의 debug 빌드가 설치돼 있고 첫 설정과 사용 기록 권한이 끝난 상태. 설치·권한은 건드리지 않는다.
set -uo pipefail
cd "$(dirname "$0")/../.."
APP=io.github.inq_o.phonedoctor
SETTLE_WAIT=7   # 판정기가 마지막 5초를 다음 검사로 미루므로, 단계가 끝나고 이만큼 기다린 뒤 검사한다
out=${1:-ml/data/poc/product_obs/scenario_$(date +%Y-%m-%d_%H%M%S)}
mkdir -p "$out"

# adb shell은 표준 입력을 읽어 가므로 막아 둔다(안 그러면 Enter 입력을 가로채 단계가 저절로 넘어간다)
adb() { command adb "$@" < /dev/null; }
err() { echo "오류: $1" >&2; exit 1; }
[ "$(adb get-state 2>/dev/null)" = "device" ] || err "연결된 기기 없음. ANDROID_SERIAL로 기기를 고정하세요"
pkgs=$(adb shell pm list packages | tr -d '\r')   # grep -q가 먼저 끝나면 pipefail에 걸리므로 변수로 받는다
grep -qx "package:$APP" <<< "$pkgs" || err "폰주치의가 설치돼 있지 않음"
now() { local ns; ns=$(adb shell date +%s%N | tr -d '\r'); [[ "$ns" =~ ^[0-9]+$ ]] || return 1; echo $(( ns / 1000000 )); }
sql() { sqlite3 "$out/phonedoctor.db" "$1"; }
pull_db() {
  local f
  for f in phonedoctor.db phonedoctor.db-wal phonedoctor.db-shm; do
    adb exec-out run-as $APP cat "databases/$f" > "$out/$f" 2>/dev/null || true
  done
  [ -s "$out/phonedoctor.db" ]
}
# 검사를 한 번 돌리고 그 기록의 id를 낸다. 실패하면 빈 값
scan() {
  local last n i
  pull_db || return 1
  last=$(sql "SELECT COALESCE(MAX(id),0) FROM scan_run WHERE trigger='adb'")
  adb shell am broadcast -f 32 -n $APP/.debug.ScanNowReceiver >/dev/null || return 1
  for i in $(seq 30); do
    sleep 1
    pull_db && n=$(sql "SELECT COALESCE(MAX(id),0) FROM scan_run WHERE trigger='adb'") && [ "${n:-0}" -gt "${last:-0}" ] && { echo "$n"; return 0; }
  done
  return 1
}

steps=(
  "A1|홈 화면에서 유튜브를 열고 영상을 30초쯤 본 뒤 홈 버튼"
  "A2|최근 앱 버튼으로 유튜브를 다시 열고, 뒤로 가기로 빠져나오기"
  "A3|네이버 앱을 열어 아무거나 검색하고 결과 하나를 연 뒤 홈 버튼"
  "A4|유튜브 영상을 재생하다 홈 버튼(작은 창으로 줄어듦) → 작은 창을 눌러 다시 크게"
  "B1|크롬에서 '유튜브'를 검색해 유튜브 링크를 눌러 유튜브 앱이 열리게 하기 → 뒤로 가기로 크롬 복귀"
  "B2|크롬에서 쇼핑 앱 상품(또는 네이버 지도 장소)을 검색해 '앱에서 열기'로 넘어가기 → 뒤로 가기로 복귀"
  "B3|메일(또는 문자)에 있는 웹 링크를 눌러 브라우저가 열리게 하기 → 뒤로 가기로 복귀"
  "B4|메일에 있는 유튜브 링크를 눌러 유튜브 앱이 열리게 하기 → 뒤로 가기로 복귀"
  "B5|네이버 앱에서 뉴스 기사 안의 외부 링크나 광고 배너를 눌러 다른 화면·앱으로 넘어가기 → 뒤로 가기로 복귀"
  "C1|유튜브에서 영상 [공유] → 메일(또는 메시지) 선택 → 작성 화면이 뜨면 뒤로 가기로 복귀"
  "C2|갤러리에서 사진 [공유] → 메일 선택 → 작성 화면이 뜨면 뒤로 가기로 복귀"
  "D1|홈 화면 상태에서 아이폰으로 이 폰에 메일 보내기 → 알림이 뜨면 눌러서 메일 앱 진입"
  "D2|유튜브를 보는 중에 아이폰으로 메일 한 통 더 보내기 → 알림을 눌러 메일 앱 진입 → 뒤로 가기"
  "E1|쇼핑 앱(또는 네이버)에서 로그인 누르기 → 구글·네이버·카카오 등 다른 화면으로 인증 → 원래 앱으로 복귀"
  "E2|쇼핑 앱에서 상품 [구매] → 결제 수단 선택 → 결제 화면·앱이 뜨면 결제하지 말고 취소해서 복귀"
  "F1|시계 앱에서 1분 타이머를 맞추고 유튜브를 보다가, 타이머가 울리면 해제"
  "F2|유튜브를 보다가 전원 버튼으로 화면 끄기 → 30초 뒤 켜고 잠금 해제"
  "F3|Play 스토어에서 무료 앱 하나를 설치하고 [열기]로 실행"
  "F4|광고가 나오는 무료 게임(또는 앱)에서 광고가 뜰 때까지 쓰기 → 광고를 눌러 Play 스토어로 넘어가기 → 뒤로 가기"
)

{
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r') sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "app_version=$(adb shell dumpsys package $APP | tr -d '\r' | grep -m1 versionName | xargs)"
  echo "repo_commit=$(git rev-parse --short HEAD)  # 맥 쪽 레포. 폰에 깔린 빌드와 다를 수 있음"
  echo "started_ms=$(now)"
} > "$out/env.txt"
echo "id,start_ms,end_ms,status,scan_id,transitions,ad,hold,stored,note,step" > "$out/steps.csv"

echo "== 준비 검사 (지금까지 쌓인 기록을 먼저 비움)"
adb shell input keyevent KEYCODE_WAKEUP >/dev/null
sleep "$SETTLE_WAIT"
base=$(scan) || err "검사가 돌지 않음. 폰주치의 첫 설정과 사용 기록 권한을 확인하세요"
[ "$(sql "SELECT result FROM scan_run WHERE id=$base")" = "OK" ] || err "준비 검사 결과가 OK가 아님: $(sql "SELECT result,note FROM scan_run WHERE id=$base")"
echo "base_scan_id=$base" >> "$out/env.txt"
echo "   통과. 총 ${#steps[@]}단계. 단계마다 Enter=시작/끝, s=건너뜀, q=끝냄"

for entry in "${steps[@]}"; do
  id=${entry%%|*}; text=${entry#*|}
  echo
  echo "[$id] $text"
  read -r -p "   시작하려면 Enter (s=건너뜀, q=끝냄): " a || break
  [ "$a" = "q" ] && break
  if [ "$a" = "s" ]; then echo "$id,,,skipped,,,,,,,\"$text\"" >> "$out/steps.csv"; continue; fi
  t0=$(now) || err "시각 읽기 실패"
  read -r -p "   끝났으면 Enter (메모가 있으면 쓰고 Enter): " note || err "입력이 끊김. $id 단계는 기록하지 않음"
  t1=$(now) || err "시각 읽기 실패"
  echo "   기록 중(약 10초)…"
  sleep "$SETTLE_WAIT"
  if sid=$(scan); then
    row=$(sql "SELECT result||','||id||','||transitionCount||','||adCount||','||holdCount||','||storedCount FROM scan_run WHERE id=$sid")
    echo "   전환·광고·보류·저장: $(echo "$row" | cut -d, -f3-6 | tr ',' ' ')"
  else
    row="scan_failed,,,,,"
    echo "   검사 기록이 생기지 않음(이 단계는 다음 검사에 섞임)"
  fi
  echo "$id,$t0,$t1,$row,\"${note//\"/\'}\",\"$text\"" >> "$out/steps.csv"
done

echo
echo "== 마무리"
pull_db || echo "DB 수집 실패" >&2
adb shell dumpsys usagestats > "$out/usagestats_dump.txt" 2>/dev/null || true   # 원자료 보관용
echo "ended_ms=$(now)" >> "$out/env.txt"
sql ".mode csv" ".headers on" "SELECT * FROM symptom_event WHERE id > 0 ORDER BY resumedAt" > "$out/symptom_event.csv" 2>/dev/null || true
sql ".mode csv" ".headers on" "SELECT * FROM alert_local" > "$out/alert_local.csv" 2>/dev/null || true
ad=$(awk -F, 'NR>1 && $4=="OK" {s+=$7} END {print s+0}' "$out/steps.csv")
echo "광고로 센 전환 합계=$ad  (0이 아니면 오탐 후보. 단계별 값은 steps.csv)"
echo "원자료: $out"
