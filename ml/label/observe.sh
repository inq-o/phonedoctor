#!/bin/bash
# 앱 하나를 관찰 전용 에뮬레이터에 설치해 사용 3분 + 홈 대기 10분 동안 증거를 남기고 지운다.
# 판정(R1~R6)은 사람이 out/ 아래 화면·로그를 보고 labels CSV에 적는다. ml/label/README.md 참고.
#
# ml/label/observe.sh <package> <apk 파일 또는 분할 APK 폴더> [out 상위 폴더]
set -euo pipefail

PKG=$1
SRC=$2
mkdir -p "${3:-ml/data/observe}/$PKG"
OUT=$(cd "${3:-ml/data/observe}/$PKG" && pwd)   # 에뮬레이터 캡처는 절대 경로가 필요
USE_SECONDS=180
BG_MINUTES=10
SEED=20261012

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/run.log"; }
# 기기 안 screencap은 FLAG_SECURE 앱에서 검게 찍혀 에뮬레이터(호스트) 쪽에서 캡처한다
shot() { adb emu screenrecord screenshot "$OUT/$1.png" > /dev/null; }
focus() { echo "$1 $(adb shell dumpsys window | grep -m1 mCurrentFocus)" >> "$OUT/focus.log"; }
# 대상 앱이 소유한 창(오버레이 포함)만 기록. 앱 밖 광고(R1)의 근거
windows() {
  echo "== $1" >> "$OUT/windows.log"
  adb shell dumpsys window windows | awk -v p="package=$PKG " '
    /Window #/ { h = $0; hit = 0 }
    index($0, p) { hit = 1; print h }
    hit && / ty=[A-Z_]/ { match($0, / ty=[A-Z_]+/); print "   " substr($0, RSTART, RLENGTH) }
    hit && /mViewVisibility/ { print "    " $1; hit = 0 }' >> "$OUT/windows.log"
}

adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard   # 이전 앱의 잠금화면 확인 뒤 남은 잠금 해제
# 앱이 남으면 다음 앱 관찰에 광고가 섞인다. 중간에 실패해도 지운다
remove_app() {
  local admin
  admin=$(adb shell dumpsys device_policy | grep -o "$PKG/[^ }:]*" | head -1) || true
  if [ -n "$admin" ]; then adb shell dpm remove-active-admin "$admin" || true; fi
  adb uninstall "$PKG" > /dev/null
}
trap 'log "aborted"; adb emu screenrecord stop > /dev/null || true; adb shell pkill -f com.android.commands.monkey || true; remove_app' EXIT
log "install $SRC"
if [ -d "$SRC" ]; then adb install-multiple -r -g "$SRC"/*.apk; else adb install -r -g "$SRC"; fi

{
  echo "package=$PKG"
  echo "date=$(date -u +%FT%TZ)"
  echo "device=$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "version=$(adb shell dumpsys package "$PKG" | grep -m1 versionName | tr -d ' \r')"
  echo "use_seconds=$USE_SECONDS bg_minutes=$BG_MINUTES seed=$SEED"
  echo "launcher=$(adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" | tail -1 | tr -d '\r')"
} > "$OUT/meta.txt"

log "use ${USE_SECONDS}s"
adb emu screenrecord start --time-limit "$USE_SECONDS" --size 540x1200 --bit-rate 500000 "$OUT/use.webm" > /dev/null
LAUNCHER=$(grep '^launcher=' "$OUT/meta.txt" | cut -d= -f2)
if [[ $LAUNCHER == */* ]]; then adb shell am start -n "$LAUNCHER" > /dev/null; else log "no launcher activity"; fi
sleep 5; shot use_00
# 입력 횟수로는 시간이 안 맞아(대기도 1회로 셈) 넉넉히 주고 시간이 되면 끊는다
adb shell monkey -p "$PKG" -s $SEED --throttle 700 --pct-syskeys 0 --pct-flip 0 --ignore-crashes --ignore-timeouts 100000 > "$OUT/monkey.log" 2>&1 &
sleep "$USE_SECONDS"
adb shell pkill -f com.android.commands.monkey || true
wait || true
shot use_99; focus use_end; windows use_end
adb emu screenrecord stop > /dev/null || true

log "home, wait ${BG_MINUTES}m"
adb shell input keyevent KEYCODE_HOME
for i in $(seq -w 1 "$BG_MINUTES"); do
  sleep 60
  shot "bg_$i"; focus "bg_$i"; windows "bg_$i"
done

# 잠금화면 광고 확인: 화면을 껐다 켜서 잠금화면 캡처
adb shell input keyevent KEYCODE_SLEEP; sleep 5
adb shell input keyevent KEYCODE_WAKEUP; sleep 3
shot lock; focus lock
adb shell wm dismiss-keyguard; sleep 1

adb shell dumpsys notification --noredact | grep -A12 "pkg=$PKG" > "$OUT/notifications.txt" || true
# 알림은 커스텀 화면이라 덤프에 글자가 없을 때가 많아 알림창을 펼쳐 찍는다
adb shell cmd statusbar expand-notifications; sleep 2; shot notifications
adb shell cmd statusbar collapse
# 덤프의 다른 구역(런처 목록 등)에도 패키지명이 나와 활성 관리자 구역만 본다
adb shell dumpsys device_policy | awk '/Enabled Device Admins/ { f = 1; next } f && /^  [A-Z]/ { f = 0 } f' \
  | grep "$PKG" > "$OUT/device_admin.txt" || true
adb shell appops get "$PKG" > "$OUT/appops.txt" || true

log "uninstall"
trap - EXIT
if ! remove_app; then log "uninstall failed"; exit 1; fi   # 남은 앱이 다음 관찰을 오염시키므로 done을 남기지 않는다
adb shell input keyevent KEYCODE_HOME
log "done"
