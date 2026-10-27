#!/usr/bin/env bash
# 장기 관측 수집. 상태를 바꾸지 않는다: force-stop·커서 삭제·버킷 설정·권한 변경·logcat clear를 하지 않음.
# 현재 버킷·권한·충전 상태를 기록하고, 누적 파일(work.csv/runs.csv)을 회전 복사로 보존한다.
# 사용: testapps/poc/longobs_collect.sh [outDir=ml/data/poc/longobs]
set -uo pipefail
cd "$(dirname "$0")/../.."
PROBE=io.github.inq_o.phonedoctor.testapps.usageprobe
out=${1:-ml/data/poc/longobs}; mkdir -p "$out/snap"
now() { echo $(( $(adb shell date +%s%N | tr -d '\r') / 1000000 )); }
t=$(now)

# 상태만 읽음(변경 없음)
bucket=$(adb shell am get-standby-bucket $PROBE | tr -d '\r')
perm=$(adb shell appops get $PROBE GET_USAGE_STATS 2>/dev/null | head -1 | tr -d '\r')
plugged=$(adb shell dumpsys battery | grep -iE 'AC|USB|powered' | tr '\n' ' ' | tr -d '\r')
runs_n=$(adb exec-out run-as $PROBE cat files/runs.csv 2>/dev/null | wc -l | tr -d ' ')

# 누적 파일을 회전 복사로 보존(원본은 지우지 않음 → 커서·이어 읽기 유지)
adb exec-out run-as $PROBE cat files/work.csv > "$out/snap/work_$t.csv" 2>/dev/null
adb exec-out run-as $PROBE cat files/runs.csv > "$out/snap/runs_$t.csv" 2>/dev/null
# logcat은 -c 없이 덤프만(버퍼를 비우지 않음)
adb logcat -d -s USAGEPROBE:I WM-WorkerWrapper:I BGAD:I >> "$out/logcat.txt" 2>/dev/null || true

printf 'event=collect\nat=%s\nbucket=%s\nperm=%s\nruns=%s\nbattery=%s\n---\n' \
  "$t" "$bucket" "$perm" "$runs_n" "$plugged" >> "$out/log.txt"
echo "collect at=$t bucket=$bucket runs=$runs_n perm=$perm"
echo "  battery: $plugged"
echo "주의: 충전/USB 연결 자체가 버킷 제한을 풀 수 있음 → 위 battery 기록 확인"
