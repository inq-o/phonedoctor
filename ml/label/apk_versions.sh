#!/bin/bash
# 라벨 붙인 앱마다 특징을 뽑은 APK(ml/data/apk)와 관찰한 APK(ml/data/apk_split)의 versionCode를 대조한다.
# 판이 다르면 그 라벨은 관찰한 판의 정답이라 설치 시점 분류기 평가에 그대로 쓰지 않는다(README §3). 다른 앱이 있으면 exit 1
# ml/label/apk_versions.sh ml/label/test_v1_labels.csv
set -uo pipefail
AAPT2=${AAPT2:-$(ls "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/aapt2 2>/dev/null | tail -1)}
[ -x "$AAPT2" ] || { echo "aapt2 없음: AAPT2=<경로>로 지정" >&2; exit 2; }
vc() { "$AAPT2" dump badging "$1" 2>/dev/null | sed -n "1s/.*versionCode='\([0-9]*\)'.*/\1/p"; }

echo "package,feature_version,observed_version,same"
bad=0
for p in $(tail -n +2 "$1" | cut -d, -f1); do
  obs=ml/data/apk_split/$p
  if [ -d "$obs" ]; then obs=$obs/$p.apk; else obs=$obs.apk; fi
  a=$(vc "ml/data/apk/$p.apk"); b=$(vc "$obs")
  if [ -n "$a" ] && [ "$a" = "$b" ]; then same=yes; else same=no; bad=$((bad + 1)); fi
  echo "$p,$a,$b,$same"
done
echo "판이 다르거나 확인 못 한 앱: $bad" >&2
[ "$bad" -eq 0 ]
