#!/usr/bin/env bash
# 제품 앱(폰주치의 debug 빌드) 증상 감지 스모크. 설치 → 권한 → 재현 앱이 앱 밖 광고를 띄움 → 검사 → 확인 요청이 열렸는지.
# 기기에서 사람이 누르는 것은 없다. 삭제까지는 끝난 뒤 폰에서 직접 눌러 확인한다(맨 아래 안내).
# ⚠️ 재현 앱 1종으로 감지 경로가 도는지만 본다. 실제 광고 앱 탐지율·오탐률 근거가 아니다.
# ⚠️ 재현 앱(bgad)을 깔고 광고를 띄운다. 시험용 기기에서만 돌린다.
# 사용: ANDROID_SERIAL=<기기> testapps/poc/product_smoke.sh [count=5] [intervalSec=20] [holdSec=4] [outDir]
# 종료: 0 확인 요청 열림 / 3 광고는 떴는데 요청이 안 열림 / 2 전제 미충족(무효) / 1 오류
set -uo pipefail
cd "$(dirname "$0")/../.."
APP=io.github.inq_o.phonedoctor
BGAD=io.github.inq_o.phonedoctor.testapps.bgad
APP_APK=app/build/outputs/apk/debug/app-debug.apk
BGAD_APK=testapps/bgad/build/outputs/apk/debug/bgad-debug.apk
count=${1:-5}; interval=${2:-20}; hold=${3:-4}
out=${4:-ml/data/poc/product_smoke/$(date +%Y-%m-%d_%H%M%S)}

err() { echo "스모크 오류: $1" >&2; exit 1; }
invalid() { echo "스모크 무효: $1" >&2; exit 2; }
command -v sqlite3 >/dev/null || err "sqlite3 없음"
[ "$(adb get-state 2>/dev/null)" = "device" ] || err "연결된 기기 없음(adb get-state). 여러 대면 ANDROID_SERIAL로 고정"
[ -f "$APP_APK" ] && [ -f "$BGAD_APK" ] || err "APK 없음. 먼저: ./gradlew :app:assembleDebug :testapps:bgad:assembleDebug"
mkdir -p "$out" || err "$out 을 만들 수 없음"
req() { adb "$@" || err "명령 실패: adb $*"; }
prop() { adb shell getprop "$1" | tr -d '\r'; }
now() { local ms; ms=$(adb shell date +%s%N 2>/dev/null | tr -d '\r') || return 1; [[ "$ms" =~ ^[0-9]+$ ]] || return 1; echo $(( ms / 1000000 )); }
# 앱 DB 사본을 가져와 SQL 실행 (WAL 파일까지 같이 가져와야 최신 값이 보임)
pull_db() {
  local f
  for f in phonedoctor.db phonedoctor.db-wal phonedoctor.db-shm; do
    adb exec-out run-as $APP cat "databases/$f" > "$out/$f" 2>/dev/null || true
  done
  [ -s "$out/phonedoctor.db" ] || return 1
}
sql() { sqlite3 -noheader "$out/phonedoctor.db" "$1"; }
# adb로 검사를 한 번 돌리고 그 기록이 생길 때까지 기다림 (작업은 비동기, 주기 검사 기록과 섞이지 않게 trigger로 구분)
scan_wait() {
  local last=0 i n
  pull_db && last=$(sql "SELECT COALESCE(MAX(id),0) FROM scan_run WHERE trigger='adb'" 2>/dev/null || echo 0)
  req shell am broadcast -f 32 -n $APP/.debug.ScanNowReceiver >/dev/null
  for i in $(seq 30); do
    sleep 1
    pull_db && n=$(sql "SELECT COALESCE(MAX(id),0) FROM scan_run WHERE trigger='adb'" 2>/dev/null) && [ "${n:-0}" -gt "${last:-0}" ] && return 0
  done
  return 1
}

api=$(prop ro.build.version.sdk)
{
  echo "at=$(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "model=$(prop ro.product.model)"
  echo "android=$(prop ro.build.version.release) api=$api"
  echo "oneui=$(prop ro.build.version.oneui)"
  echo "patch=$(prop ro.build.version.security_patch)"
  echo "commit=$(git rev-parse --short HEAD)$(git diff --quiet -- . ':!ml/label' || echo '+수정')"
  echo "app_apk_sha256=$(shasum -a 256 "$APP_APK" | cut -d' ' -f1)"
  echo "args=count:$count interval:$interval hold:$hold"
  echo "battery=$(adb shell dumpsys battery | tr -d '\r' | grep -E 'level|AC powered|USB powered' | tr -s ' ' | tr '\n' ';')"
} > "$out/env.txt" || err "env.txt 기록 실패"

cleanup() { adb shell am force-stop $BGAD >/dev/null 2>&1; }
trap cleanup EXIT

echo "== 설치 ($(prop ro.product.model), Android $(prop ro.build.version.release))"
req install -r "$APP_APK" >/dev/null
req install -r -g "$BGAD_APK" >/dev/null
# 사용 기록 보기: 시니어가 설정에서 켜는 것을 adb로 대신함 (온보딩 화면 시험은 사람이 따로)
req shell appops set $APP GET_USAGE_STATS allow
[ "$api" -ge 33 ] && adb shell pm grant $APP android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
# 재현 앱이 배경에서 화면을 띄우려면 필요 (실제 광고 앱도 이 권한을 받아 둔다)
req shell appops set $BGAD SYSTEM_ALERT_WINDOW allow
adb shell am force-stop $BGAD
adb logcat -c

# 앱을 한 번 연다(한 번도 안 연 앱은 주기 작업 할당량이 없음). 첫 검사에서 판정 시작 시각이 정해진다
adb shell am start -W -n $APP/.ui.MainActivity 2>&1 | grep -q 'Status: ok' || err "폰주치의 실행 실패"
scan_wait || err "첫 검사 기록이 생기지 않음(작업이 돌지 않음)"
first=$(sql "SELECT result FROM scan_run WHERE trigger='adb' ORDER BY id DESC LIMIT 1")
[ "$first" = "OK" ] || invalid "첫 검사 결과가 $first (사용 기록 보기·잠금 상태 확인)"
alerts_before=$(sql "SELECT COUNT(*) FROM alert_local WHERE packageName='$BGAD' AND status='OPEN'")
events_before=$(sql "SELECT COUNT(*) FROM symptom_event WHERE packageName='$BGAD'")
req shell input keyevent KEYCODE_HOME
sleep 4   # 판정 시작 직후 3초는 "직전 조작 없음"을 확정하지 못해 보류가 난다

echo "== 광고 ${count}번 (간격 ${interval}초, 유지 ${hold}초). 약 $(( count * interval + 15 ))초 걸림"
t0=$(now) || err "시각 읽기 실패"
adb shell am start -W -n $BGAD/.MainActivity --es mode saw --ei count "$count" --ei intervalSec "$interval" --ei holdSec "$hold" >/dev/null
req shell input keyevent KEYCODE_HOME
sleep $(( count * interval + hold + 3 ))
adb logcat -d -s BGAD:I > "$out/truth.log" 2>/dev/null || err "광고 로그 수집 실패"
shows=$(grep -c ' SHOW ' "$out/truth.log"); resumed=$(grep -c ' RESUMED ' "$out/truth.log")
# 전제: 광고 화면이 실제로 재개됐는지. SHOW만으로는 표시 확인이 아님
[ "$resumed" -ge 1 ] || invalid "광고 화면이 재개되지 않음(SHOW $shows, RESUMED 0). 배경 실행 제한·알람 제한 가능"

sleep 6   # 판정기는 조회 끝 5초 전까지만 확정한다
scan_wait || err "검사 기록이 생기지 않음"
adb logcat -d -s PhoneDoctorScan:V WM-WorkerWrapper:I > "$out/app.log" 2>/dev/null
adb shell dumpsys notification --noredact 2>/dev/null | grep -A3 "pkg=$APP" > "$out/notification.txt" 2>/dev/null

sqlite3 -header -csv "$out/phonedoctor.db" "SELECT * FROM scan_run ORDER BY id" > "$out/scan_run.csv"
sqlite3 -header -csv "$out/phonedoctor.db" "SELECT * FROM symptom_event ORDER BY resumedAt" > "$out/symptom_event.csv"
sqlite3 -header -csv "$out/phonedoctor.db" "SELECT * FROM alert_local" > "$out/alert_local.csv"
last=$(sql "SELECT result||' 전환 '||transitionCount||' 광고 '||adCount||' 보류 '||holdCount||' 저장 '||storedCount FROM scan_run WHERE trigger='adb' ORDER BY id DESC LIMIT 1")
events=$(( $(sql "SELECT COUNT(*) FROM symptom_event WHERE packageName='$BGAD' AND resumedAt >= $t0") ))
alert=$(sql "SELECT status||' '||date||' '||count||'번' FROM alert_local WHERE packageName='$BGAD'")
open=$(sql "SELECT COUNT(*) FROM alert_local WHERE packageName='$BGAD' AND status='OPEN'")
noti=0; [ -s "$out/notification.txt" ] && noti=1
threshold=$(grep -o 'DAILY_THRESHOLD = [0-9]*' core/data/src/main/kotlin/io/github/inq_o/phonedoctor/core/data/SymptomPolicy.kt | grep -o '[0-9]*$')
{
  echo "표시 시도(SHOW)=$shows 재개 확인(RESUMED)=$resumed"
  echo "마지막 검사: $last"
  echo "광고로 저장한 전환(이번 실행)=$events  기준=하루 ${threshold}번(임시값)"
  echo "확인 요청: ${alert:-없음}  (이전 실행에서 이미 열려 있었음=$alerts_before, 이전 저장 전환=$events_before)"
  echo "알림 표시(dumpsys)=$noti"
} | tee "$out/result.txt"
echo "원자료: $out"

if [ "$open" -ge 1 ]; then
  cat <<MSG
== 통과: 확인 요청이 열렸습니다.
이어서 폰에서 직접 확인할 것 (사람이 누름):
  1. 알림 "확인이 필요한 앱이 있어요"를 누르거나 폰주치의를 엽니다
  2. bgad 카드 → [이 앱 지우기] → 시스템 창에서 확인 → "지웠어요" 화면
  3. 취소해 보기: 시스템 창에서 취소하면 "아직 지워지지 않았어요"와 [다시 지우기]가 나와야 합니다
MSG
  exit 0
fi
[ "$resumed" -lt "$threshold" ] && invalid "재개된 광고가 기준(${threshold}번)보다 적음"
echo "== 실패: 광고는 ${resumed}번 재개됐는데 확인 요청이 열리지 않았습니다. $out/scan_run.csv·symptom_event.csv 확인" >&2
exit 3
