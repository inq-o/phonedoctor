#!/usr/bin/env bash
# 장기 관측 1회 초기 설정. 설치 → 권한 → 시작 화면 한 번 열기 → 주기 작업 시작.
# 이후 관측은 longobs_collect.sh로만 하고, 이 스크립트를 다시 돌리지 않는다(상태 초기화됨).
# 사용: ANDROID_SERIAL=<기기> testapps/poc/longobs_setup.sh [outDir=ml/data/poc/longobs]
set -uo pipefail
cd "$(dirname "$0")/../.."
PROBE=io.github.inq_o.phonedoctor.testapps.usageprobe
BGAD=io.github.inq_o.phonedoctor.testapps.bgad
out=${1:-ml/data/poc/longobs}; mkdir -p "$out"

die() { echo "setup 실패: $1" >&2; exit 1; }
# 기기 1대 고정·연결 확인 (ANDROID_SERIAL 권장). 연결 실패를 성공으로 넘기지 않음
[ "$(adb get-state 2>/dev/null)" = "device" ] || die "연결된 기기 없음(adb get-state). ANDROID_SERIAL로 기기를 고정하세요"
req() { adb "$@" || die "명령 실패: adb $*"; }    # 필수 명령은 실패 시 중단
now() {
  local ms; ms=$(adb shell date +%s%N 2>/dev/null | tr -d '\r') || die "시각 읽기 실패"
  [[ "$ms" =~ ^[0-9]+$ ]] || die "시각 형식 오류: $ms"
  echo $(( ms / 1000000 ))
}

req install -r testapps/usageprobe/build/outputs/apk/debug/usageprobe-debug.apk
req install -r -g testapps/bgad/build/outputs/apk/debug/bgad-debug.apk
req shell appops set $PROBE GET_USAGE_STATS allow
# 실제 온보딩처럼 앱을 한 번 연다(대기 버킷을 NEVER에서 올림). 이후 다시 열지 않음
req shell am start -W -n $PROBE/.MainActivity
req shell input keyevent KEYCODE_HOME
req shell am broadcast -f 32 -n $PROBE/.WorkReceiver --es action start
bucket=$(adb shell am get-standby-bucket $PROBE 2>/dev/null | tr -d '\r') || die "버킷 읽기 실패"
printf 'event=setup\nat=%s\nbucket=%s\n' "$(now)" "$bucket" >> "$out/log.txt"
echo "setup 완료 (기기 $(adb get-serialno)): $out/log.txt. 이후 손대지 말고 longobs_collect.sh만 사용"
