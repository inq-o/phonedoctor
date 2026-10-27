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
# ⚠️ adb exec-out은 원격 명령이 실패해도 exit 0을 돌려준다(연결만 반영, AOSP commandline.cpp).
# 그래서 원격 종료 코드를 sentinel(__RC:N)로 직접 받아 분기한다. RC_OUT/RC_RC에 결과.
remote() {  # $1=run-as 아래 실행할 셸 명령. stdout → RC_OUT, 원격 종료 코드 → RC_RC
  local o; o=$(adb exec-out run-as $PROBE sh -c "$1; echo __RC:\$?" 2>/dev/null)
  RC_RC=$(printf '%s\n' "$o" | sed -n 's/^__RC:\([0-9][0-9]*\)$/\1/p' | tail -1)
  RC_OUT=$(printf '%s\n' "$o" | sed '/^__RC:[0-9][0-9]*$/d')
  [ -n "$RC_RC" ] || RC_RC=255   # sentinel이 없으면 run-as 자체 실패(접근 불가)로 간주
}
remote "ls -1 files"; [ "$RC_RC" = 0 ] || die "run-as 접근 실패(디버그 빌드/권한 확인, rc=$RC_RC)"
ls_out=$RC_OUT

# 각 파일: 미생성(실행 전) / 전송 실패(원격 rc≠0 또는 접근 불가) / 정상 수집을 구분
pull() {  # $1=파일명 → 상태 문자열 echo, 성공 시에만 스냅샷 저장
  local name=$1
  echo "$ls_out" | grep -qx "$name" || { echo "미생성"; return; }   # 작업이 아직 안 만듦
  remote "cat files/$name"
  [ "$RC_RC" = 0 ] || { echo "전송실패(rc=$RC_RC)"; return; }         # 오류 텍스트를 CSV로 저장하지 않음
  local f="$out/snap/${name%.csv}_$t.csv"
  printf '%s\n' "$RC_OUT" > "$f" || { rm -f "$f"; echo "저장실패"; return; }   # 디스크·권한 문제로 불완전한 스냅샷을 남기지 않음
  echo "수집($(printf '%s' "$RC_OUT" | grep -c . || true)행)"
}
runs_st=$(pull runs.csv)
work_st=$(pull work.csv)
# logcat: 한 파일에 >>로 붙이면 수집마다 버퍼 전체가 중복된다 → 회차별 스냅샷 파일로. 실패도 상태로 남김
if adb logcat -d -s USAGEPROBE:I WM-WorkerWrapper:I BGAD:I > "$out/snap/logcat_$t.txt" 2>/dev/null; then log_st=ok; else log_st=실패; rm -f "$out/snap/logcat_$t.txt"; fi

# 전송 실패가 하나라도 있으면 그 회차를 부분 실패로 표시(정상 수집으로 확정 안 함)
status=ok; case "$runs_st$work_st" in *전송실패*|*저장실패*) status=부분실패;; esac
[ "$log_st" = 실패 ] && status=부분실패
printf 'event=collect\nat=%s\nstatus=%s\nbucket=%s\nperm=%s\nruns=%s\nwork=%s\nlogcat=%s\nbattery=%s\n---\n' \
  "$t" "$status" "$bucket" "$perm" "$runs_st" "$work_st" "$log_st" "$plugged" >> "$out/log.txt" || die "log.txt 기록 실패(스냅샷은 snap/에 있음)"
echo "collect at=$t status=$status bucket=$bucket runs=$runs_st work=$work_st perm=$perm"
echo "  battery: $plugged"
[ "$runs_st" = "미생성" ] && echo "  (runs.csv 미생성: 작업이 아직 한 번도 안 돎 — 수집 실패와 다름)"
echo "주의: 충전/USB 연결 자체가 버킷 제한을 풀 수 있음 → 위 battery 기록 확인"
[ "$status" = "부분실패" ] && exit 3 || exit 0   # 3 = 부분 전송 실패
