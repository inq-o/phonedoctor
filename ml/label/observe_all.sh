#!/bin/bash
# 테스트셋 전체를 차례로 관찰한다. 끝난 앱(run.log에 done)은 건너뛰므로 중간에 멈춰도 다시 실행하면 이어진다.
# ml/label/observe_all.sh ml/label/test_v1.csv
set -uo pipefail
OBS=ml/data/observe
for pkg in $(tail -n +2 "$1" | cut -d, -f1); do
  grep -q '] done' "$OBS/$pkg/run.log" 2>/dev/null && continue
  src=ml/data/apk_split/$pkg
  [ -d "$src" ] || src=$src.apk
  if [ ! -e "$src" ]; then echo "$pkg: APK 없음" >&2; continue; fi
  rm -rf "${OBS:?}/$pkg"   # 중간에 끊긴 기록은 버리고 처음부터
  ml/label/observe.sh "$pkg" "$src" < /dev/null || echo "$pkg: 실패 $?" >&2
  if [ -n "$(adb shell pm path "$pkg" < /dev/null)" ]; then
    echo "$pkg: 제거 안 됨, 다음 관찰이 오염되므로 중단" >&2; exit 1
  fi
done
