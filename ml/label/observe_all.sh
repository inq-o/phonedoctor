#!/bin/bash
# 테스트셋 전체를 차례로 관찰한다. 끝난 앱(run.log에 done)은 건너뛰므로 중간에 멈춰도 다시 실행하면 이어진다.
# 끝날 때 exit 0 = 모두 관찰, 1 = 관찰 실패·APK 없음이 있음(다시 실행하면 그 앱만 다시 관찰),
# 2 = 표본 목록을 못 읽었거나, 표본 앱이 기기에 남았거나 남았는지 확인하지 못해 중단(다음 관찰이 오염되므로 다음 앱을 시작하지 않음)
# ml/label/observe_all.sh ml/label/test_v1.csv
set -uo pipefail
OBS=ml/data/observe
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
  ml/label/observe.sh "$pkg" "$src" < /dev/null
  rc=$?
  check_clean "$pkg"
  if [ "$rc" -ne 0 ]; then echo "$pkg: 관찰 실패(exit $rc)" >&2; failed="$failed $pkg"; fi
done
if [ -n "$failed" ]; then echo "끝내지 못한 앱:$failed" >&2; exit 1; fi
