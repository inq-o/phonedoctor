#!/bin/bash
# 테스트셋 전체를 차례로 관찰한다. 끝난 앱(run.log에 done)은 건너뛰므로 중간에 멈춰도 다시 실행하면 이어진다.
# 끝날 때 exit 0 = 모두 관찰, 1 = 관찰 실패·APK 없음이 있음(다시 실행하면 그 앱만 다시 관찰),
# 2 = 표본 목록을 못 읽었거나, 표본 앱이 기기에 남았거나 남았는지 확인하지 못해 중단(다음 관찰이 오염되므로 다음 앱을 시작하지 않음)
# 출력 폴더(기본 ml/data/observe)를 주면 그 아래에 남기고 그 안의 기록만 보고 건너뛴다. 다시 관찰할 때는 새 폴더를 준다(README §3)
# ml/label/observe_all.sh ml/label/test_v1.csv [출력 폴더]
set -uo pipefail
OBS=${2:-ml/data/observe}
# 출력 폴더가 APK 입력 폴더이거나 그 안이면 아래 rm -rf가 입력 APK를 지운다. 심볼릭 링크로 돌아가도 막게 물리 경로로 비교하고,
# 이후에도 확인한 물리 경로를 쓴다
obs_abs=$(mkdir -p "$OBS" && cd -P "$OBS" && pwd -P) || { echo "$OBS: 출력 폴더를 만들지 못함" >&2; exit 2; }
split_abs=$(cd -P ml/data/apk_split 2>/dev/null && pwd -P) || split_abs=
case "$obs_abs/" in "${split_abs:-//none}/"*) echo "$OBS: APK 입력 폴더(ml/data/apk_split) 안은 출력 폴더로 쓸 수 없음" >&2; exit 2 ;; esac
OBS=$obs_abs
PKGS=$(tail -n +2 "$1" | cut -d, -f1) && [ -n "$PKGS" ] || { echo "$1: 표본 목록을 읽지 못함" >&2; exit 2; }

# 기기에 깔린 표본 앱을 출력한다. 기기 목록을 읽지 못하면 1
leftover() {
  local out
  out=$(adb shell pm list packages < /dev/null) || return 1
  out=${out//$'\r'/}
  grep -qx package:android <<< "$out" || return 1   # 빈 출력을 "남은 앱 없음"으로 읽지 않게
  grep -Fx -f <(sed 's/^/package:/' <<< "$PKGS") <<< "$out" | cut -d: -f2
  return 0
}
check_clean() {
  local left
  if ! left=$(leftover); then echo "$1: 기기 앱 목록을 읽지 못함(연결 확인), 중단" >&2; exit 2; fi
  if [ -n "$left" ]; then echo "$1: 표본 앱이 남아 있음(${left//$'\n'/ }), 지운 뒤 다시 실행" >&2; exit 2; fi
}

check_clean "시작 전"
failed=
for pkg in $PKGS; do
  grep -q '] done' "$OBS/$pkg/run.log" 2>/dev/null && continue
  src=ml/data/apk_split/$pkg
  [ -d "$src" ] || src=$src.apk
  if [ ! -e "$src" ]; then echo "$pkg: APK 없음" >&2; failed="$failed $pkg"; continue; fi
  rm -rf "${OBS:?}/${pkg:?}"   # 중간에 끊긴 기록은 버리고 처음부터
  ml/label/observe.sh "$pkg" "$src" "$OBS" < /dev/null
  rc=$?
  check_clean "$pkg"
  if [ "$rc" -ne 0 ]; then echo "$pkg: 관찰 실패(exit $rc)" >&2; failed="$failed $pkg"; fi
done
if [ -n "$failed" ]; then echo "끝내지 못한 앱:$failed" >&2; exit 1; fi
