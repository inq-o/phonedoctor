#!/usr/bin/env bash
# 장기 관측 수집. 상태를 바꾸지 않는다: force-stop·커서 삭제·버킷 설정·권한 변경·logcat clear를 하지 않음.
# 실패와 "실제로 0회/빈 결과"를 구분한다. 연결·명령 실패 시 빈 스냅샷을 정상으로 남기지 않음.
# 사용: ANDROID_SERIAL=<기기> testapps/poc/longobs_collect.sh [outDir=ml/data/poc/longobs]
set -uo pipefail
cd "$(dirname "$0")/../.."
PROBE=io.github.inq_o.phonedoctor.testapps.usageprobe
out=${1:-ml/data/poc/longobs}; mkdir -p "$out/snap"

die() { echo "collect 실패: $1" >&2; exit 1; }
[ "$(adb get-state 2>/dev/null)" = "device" ] || die "연결된 기기 없음. ANDROID_SERIAL로 기기를 고정하세요"
now() {
  local ms; ms=$(adb shell date +%s%N 2>/dev/null | tr -d '\r') || die "시각 읽기 실패"
  [[ "$ms" =~ ^[0-9]+$ ]] || die "시각 형식 오류: $ms"
  echo $(( ms / 1000000 ))
}
t=$(now)

# 상태만 읽음(변경 없음). 각 값은 못 읽으면 'NA'로 구분(빈칸 아님)
read_or_na() { local v; v=$("$@" 2>/dev/null | tr -d '\r'); [ -n "$v" ] && echo "$v" || echo NA; }
bucket=$(read_or_na adb shell am get-standby-bucket $PROBE)
perm=$(adb shell appops get $PROBE GET_USAGE_STATS 2>/dev/null | head -1 | tr -d '\r'); perm=${perm:-NA}
plugged=$(adb shell dumpsys battery 2>/dev/null | grep -iE 'AC|USB|powered' | tr '\n' ' ' | tr -d '\r'); plugged=${plugged:-NA}

# 앱이 설치돼 있고 run-as가 되는지 먼저 확인 → "앱 미설치/접근 불가"와 "실행 0회"를 구분
if ! adb shell pm list packages | grep -q "$PROBE"; then die "$PROBE 미설치 — 먼저 longobs_setup.sh"; fi
runs_raw=$(adb exec-out run-as $PROBE cat files/runs.csv 2>/dev/null) || die "run-as 접근 실패(디버그 빌드/권한 확인)"
runs_n=$(printf '%s' "$runs_raw" | grep -c . || true)

# 누적 파일을 회전 복사로 보존(원본은 지우지 않음 → 커서·이어 읽기 유지). 빈 결과면 스냅샷을 만들지 않음
printf '%s' "$runs_raw" | grep -q . && printf '%s\n' "$runs_raw" > "$out/snap/runs_$t.csv"
work_raw=$(adb exec-out run-as $PROBE cat files/work.csv 2>/dev/null || true)
printf '%s' "$work_raw" | grep -q . && printf '%s\n' "$work_raw" > "$out/snap/work_$t.csv"
adb logcat -d -s USAGEPROBE:I WM-WorkerWrapper:I BGAD:I >> "$out/logcat.txt" 2>/dev/null || true

printf 'event=collect\nat=%s\nbucket=%s\nperm=%s\nruns=%s\nbattery=%s\n---\n' \
  "$t" "$bucket" "$perm" "$runs_n" "$plugged" >> "$out/log.txt"
echo "collect at=$t bucket=$bucket runs=$runs_n perm=$perm"
echo "  battery: $plugged"
[ "$runs_n" -eq 0 ] && echo "  (runs=0: 작업이 아직 한 번도 안 돎 — 수집 실패와 다름)"
echo "주의: 충전/USB 연결 자체가 버킷 제한을 풀 수 있음 → 위 battery 기록 확인"
