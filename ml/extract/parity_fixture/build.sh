#!/usr/bin/env bash
# 일치 테스트(T024)용 작은 APK를 만든다. 우리가 쓴 매니페스트만 들어 있다 (다른 앱 APK는 레포에 넣지 않음)
# 사용: ml/extract/parity_fixture/build.sh   → core/classifier/src/test/resources/parity/<이름>.apk
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
# 판을 고정한다: 다른 판으로 만들면 APK 바이트(크기 특징·SHA-256)가 바뀌어 기대값을 다시 만들어야 함
aapt2=${AAPT2:-$sdk/build-tools/36.0.0/aapt2}
jar=$sdk/platforms/android-36/android.jar
[ -x "$aapt2" ] && [ -f "$jar" ] || { echo "build-tools 36.0.0과 platforms/android-36이 필요" >&2; exit 1; }
out="$root/core/classifier/src/test/resources/parity"
mkdir -p "$out"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for d in "$here"/*/; do
  name=$(basename "$d")
  "$aapt2" compile --dir "$d/res" -o "$tmp/$name.zip"
  "$aapt2" link -o "$out/$name.apk" -I "$jar" --manifest "$d/AndroidManifest.xml" "$tmp/$name.zip"
  echo "$out/$name.apk"
done
