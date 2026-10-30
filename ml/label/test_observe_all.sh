#!/bin/bash
# observe_all.sh의 실패 처리 검사. 실제 기기 없이 가짜 adb·observe.sh로 돌린다.
# bash ml/label/test_observe_all.sh
set -u
TARGET=$(cd "$(dirname "$0")" && pwd)/observe_all.sh
fails=0

# 표본 a.pkg, b.pkg. 가짜 adb는 `pm list packages`에 $T/installed를 돌려주고 FAIL_AFTER번째 호출부터 연결 끊김으로 실패한다.
# 가짜 observe.sh는 FAIL_PKG면 exit 7, LEAVE_PKG면 앱을 남긴다.
setup() {
  T=$(mktemp -d)
  mkdir -p "$T/ml/label" "$T/ml/data/apk_split" "$T/bin"
  printf 'package,group\na.pkg,gray\nb.pkg,senior\n' > "$T/test.csv"
  touch "$T/ml/data/apk_split/a.pkg.apk" "$T/ml/data/apk_split/b.pkg.apk" "$T/installed"
  cat > "$T/bin/adb" <<'EOF'
#!/bin/bash
n=$(($(cat "$T/calls" 2>/dev/null || echo 0) + 1)); echo $n > "$T/calls"
if [ -n "${FAIL_AFTER:-}" ] && [ "$n" -ge "$FAIL_AFTER" ]; then echo "error: device offline" >&2; exit 1; fi
[ "$*" = "shell pm list packages" ] || exit 1
[ -n "${EMPTY:-}" ] && exit 0
{ echo package:android; cat "$T/installed"; } | sed "s/\$/${CR:-}/"
EOF
  cat > "$T/ml/label/observe.sh" <<'EOF'
#!/bin/bash
echo "$1" >> "$T/observed"
[ "$1" = "${LEAVE_PKG:-}" ] && echo "package:$1" >> "$T/installed"
[ "$1" = "${FAIL_PKG:-}" ] && exit 7
mkdir -p "${3:?}/$1" && echo "[00:00:00] done" > "$3/$1/run.log"
EOF
  chmod +x "$T/bin/adb" "$T/ml/label/observe.sh"
}
run() { (cd "$T" && PATH="$T/bin:$PATH" T="$T" "$@" bash "$TARGET" test.csv ${OUT_DIR:-} > "$T/out" 2>&1); echo $?; }
observed() { tr '\n' ' ' 2>/dev/null < "$T/observed"; }
expect() {  # 이름, 기대 exit, 실제 exit, 기대 관찰 목록
  local got; got=$(observed)
  if [ "$2" = "$3" ] && [ "$4" = "$got" ]; then echo "ok   $1"; else
    echo "FAIL $1: exit $3 (기대 $2), 관찰 [$got] (기대 [$4])"; sed 's/^/     /' "$T/out"; fails=$((fails + 1)); fi
  rm -rf "$T"
}

setup; expect "모두 성공" 0 "$(run env)" "a.pkg b.pkg "
setup; expect "기기 응답에 CR" 0 "$(run env CR=$'\r')" "a.pkg b.pkg "
setup; rm "$T/test.csv"; expect "표본 목록을 못 읽음" 2 "$(run env)" ""
setup; expect "처음부터 연결 끊김" 2 "$(run env FAIL_AFTER=1)" ""
setup; expect "목록이 빔(package:android 없음)" 2 "$(run env EMPTY=1)" ""
setup; echo package:b.pkg > "$T/installed"; expect "시작 전 표본 앱이 남음" 2 "$(run env)" ""
setup; expect "관찰 실패, 제거는 확인" 1 "$(run env FAIL_PKG=a.pkg)" "a.pkg b.pkg "
setup; expect "관찰 성공, 앱이 남음" 2 "$(run env LEAVE_PKG=a.pkg)" "a.pkg "
setup; expect "관찰 실패, 앱이 남음" 2 "$(run env FAIL_PKG=a.pkg LEAVE_PKG=a.pkg)" "a.pkg "
setup; expect "첫 앱 뒤 제거 확인만 실패" 2 "$(run env FAIL_AFTER=2)" "a.pkg "
setup; expect "마지막 앱 뒤 제거 확인만 실패" 2 "$(run env FAIL_AFTER=3)" "a.pkg b.pkg "
setup; rm "$T/ml/data/apk_split/a.pkg.apk"; expect "APK 없음" 1 "$(run env)" "b.pkg "
setup; run env FAIL_PKG=a.pkg > /dev/null; rm "$T/observed"
expect "다시 실행하면 실패한 앱만" 0 "$(run env)" "a.pkg "
setup; run env > /dev/null; rm "$T/observed"
got=$(OUT_DIR=ml/data/observe_runs/r2 run env)
[ -f "$T/ml/data/observe/a.pkg/run.log" ] && [ -f "$T/ml/data/observe_runs/r2/b.pkg/run.log" ] || got="기존 기록 사라짐 또는 새 폴더에 없음"
expect "새 출력 폴더: 기존 기록을 건드리지 않고 전부 다시" 0 "$got" "a.pkg b.pkg "

[ $fails -eq 0 ] && echo "모두 통과" || { echo "실패 ${fails}개"; exit 1; }
