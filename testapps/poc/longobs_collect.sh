#!/usr/bin/env bash
# 장기 관측 수집. 상태를 바꾸지 않는다: force-stop·커서 삭제·버킷 설정·권한 변경·logcat clear를 하지 않음.
# 각 상태를 구분한다: 연결 실패 / 시각 실패 / 앱 미설치·접근 실패 / 파일 미생성(실행 0회) / 전송 실패 / 정상.
# 사용: ANDROID_SERIAL=<기기> testapps/poc/longobs_collect.sh [outDir=ml/data/poc/longobs]
set -uo pipefail
cd "$(dirname "$0")/../.."
PROBE=io.github.inq_o.phonedoctor.testapps.usageprobe
out=${1:-ml/data/poc/longobs}; mkdir -p "$out/snap"

die() { echo "collect 실패: $1" >&2; exit 1; }
[ "$(adb get-state 2>/dev/null)" = "device" ] || die "연결된 기기 없음. ANDROID_SERIAL로 기기를 고정하세요"
now() {  # 서브셸용: die 대신 return 1, 부모가 || die
  local ms; ms=$(adb shell date +%s%N 2>/dev/null | tr -d '\r') || return 1
  [[ "$ms" =~ ^[0-9]+$ ]] || return 1
  echo $(( ms / 1000000 ))
}
t=$(now) || die "시각 읽기 실패"

# 상태만 읽음(변경 없음). 못 읽으면 NA로 구분
read_or_na() { local v; v=$("$@" 2>/dev/null | tr -d '\r'); [ -n "$v" ] && echo "$v" || echo NA; }
bucket=$(read_or_na adb shell am get-standby-bucket $PROBE)
perm=$(adb shell appops get $PROBE GET_USAGE_STATS 2>/dev/null | head -1 | tr -d '\r'); perm=${perm:-NA}
plugged=$(adb shell dumpsys battery 2>/dev/null | grep -iE 'AC|USB|powered' | tr '\n' ' ' | tr -d '\r'); plugged=${plugged:-NA}

adb shell pm list packages 2>/dev/null | grep -q "$PROBE" || die "$PROBE 미설치 — 먼저 longobs_setup.sh"
# run-as 접근 자체가 되는지 확인(디버그 빌드/권한). 파일 '목록'으로 미생성과 접근 실패를 구분
ls_out=$(adb exec-out run-as $PROBE ls files 2>/dev/null) || die "run-as 접근 실패(디버그 빌드/권한 확인)"

# 각 파일: 미생성(실행 전) / 전송 실패 / 정상 수집을 구분
pull() {  # $1=파일명 → 상태 문자열 echo, 성공 시 스냅샷 저장
  local name=$1 raw
  echo "$ls_out" | grep -qx "$name" || { echo "미생성"; return; }   # 작업이 아직 안 만듦
  raw=$(adb exec-out run-as $PROBE cat "files/$name" 2>/dev/null) || { echo "전송실패"; return; }
  printf '%s\n' "$raw" > "$out/snap/${name%.csv}_$t.csv"
  echo "수집($(printf '%s' "$raw" | grep -c . || true)행)"
}
runs_st=$(pull runs.csv)
work_st=$(pull work.csv)
adb logcat -d -s USAGEPROBE:I WM-WorkerWrapper:I BGAD:I >> "$out/logcat.txt" 2>/dev/null || true

# 전송 실패가 하나라도 있으면 그 회차를 부분 실패로 표시(정상 수집으로 확정 안 함)
status=ok; case "$runs_st$work_st" in *전송실패*) status=부분실패;; esac
printf 'event=collect\nat=%s\nstatus=%s\nbucket=%s\nperm=%s\nruns=%s\nwork=%s\nbattery=%s\n---\n' \
  "$t" "$status" "$bucket" "$perm" "$runs_st" "$work_st" "$plugged" >> "$out/log.txt"
echo "collect at=$t status=$status bucket=$bucket runs=$runs_st work=$work_st perm=$perm"
echo "  battery: $plugged"
[ "$runs_st" = "미생성" ] && echo "  (runs.csv 미생성: 작업이 아직 한 번도 안 돎 — 수집 실패와 다름)"
echo "주의: 충전/USB 연결 자체가 버킷 제한을 풀 수 있음 → 위 battery 기록 확인"
[ "$status" = "부분실패" ] && exit 3 || exit 0   # 3 = 부분 전송 실패
