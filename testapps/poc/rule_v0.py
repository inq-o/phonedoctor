"""증상 감지 전환 분류 규칙 v0 (FR-012, 실증 E3). ACTIVITY_RESUMED 하나를 '앱 밖 광고로 셀지' 판정한다.

조건 (모두 만족하면 광고로 셈):
  R1 시스템 앱이 아님 (spec: 판정 대상은 사용자 설치 앱)
  R2 같은 앱 안 전환이 아님 = 직전 RESUMED가 다른 패키지, 또는 그 뒤 화면이 꺼졌거나 그 앱의 직전 화면이
     2초보다 전에 멈춤 (앱 안 전환·앱 안 광고 R6은 직전 화면 PAUSED와 거의 동시에 RESUMED.
     화면을 끄기 전 쓰던 앱이 나중에 띄운 광고는 셈 — E1 화면 꺼짐에서 확인)
  R3 직전 3초 안에 그 앱의 USER_INTERACTION 없음 (알림 탭 등)
  R4 화면 켜짐·잠금 해제 3초 안에, 화면이 꺼지기 전 보던 화면(같은 앱·같은 액티비티)으로 돌아온 게 아님
     (앱이 스스로 화면을 켜고 다른 액티비티를 띄우면 셈)
  R5 런처 아이콘이 여는 액티비티(별칭이면 targetActivity)가 아님 (사용자가 아이콘으로 연 실행 제외)

사용: python3 testapps/poc/rule_v0.py <launchers.csv> <e1_saw truth.log> <e1_saw events.csv> <e2 windows.csv> <e2 events.csv> [--no-system-filter]
"""
import csv
import re
import sys

RESUMED, PAUSED, USER_INTERACTION = "1", "2", "7"
SCREEN_ON, SCREEN_OFF, KEYGUARD_HIDDEN = "15", "16", "18"
LOOKBACK_MS = 3000
HANDOFF_MS = 2000


def flags(events, launchers, system_filter=True):
    """events: 시간순 dict(timeStamp,type,package,class). 반환: RESUMED 이벤트 index -> 못 넘은 조건 목록 ([]면 광고로 셈)"""
    out = {}
    prev_pkg = None          # 직전 RESUMED 패키지
    prev_t = 0
    last_off = -1
    last_paused = {}         # 패키지 -> 마지막 PAUSED 시각
    before_off = None        # 화면이 꺼질 때의 포그라운드 (패키지, 클래스)
    prev_cls = None
    for i, e in enumerate(events):
        t, typ, pkg = int(e["timeStamp"]), e["type"], e["package"]
        if typ == SCREEN_OFF:
            before_off = (prev_pkg, prev_cls)
            last_off = t
        if typ == PAUSED:
            last_paused[pkg] = t
        if typ != RESUMED:
            continue
        info = launchers.get(pkg)
        failed = []
        if system_filter and (info is None or info["system"]):
            failed.append("R1")
        if prev_pkg == pkg and last_off < prev_t and t - last_paused.get(pkg, -10**12) <= HANDOFF_MS:
            failed.append("R2")
        recent = [x for x in events[max(0, i - 50):i] if t - int(x["timeStamp"]) <= LOOKBACK_MS]
        if any(x["type"] == USER_INTERACTION and x["package"] == pkg for x in recent):
            failed.append("R3")
        if any(x["type"] in (SCREEN_ON, KEYGUARD_HIDDEN) for x in recent) and before_off == (pkg, e["class"]):
            failed.append("R4")
        if info and e["class"] == info["resolved"]:
            failed.append("R5")
        out[i] = failed
        prev_pkg, prev_t, prev_cls = pkg, t, e["class"]
    return out


def load_launchers(path):
    m = {}
    for r in csv.DictReader(open(path, encoding="utf-8")):
        m.setdefault(r["package"], {"resolved": r["resolvedClass"], "system": r["system"] == "true"})
    return m


def evaluate(launchers, truth_log, e1_csv, windows_csv, e2_csv, system_filter=True):
    shows = [int(m[1]) for m in (re.search(r" SHOW mode=\w+ seq=\d+ t=(\d+)", l) for l in open(truth_log)) if m]
    e1 = list(csv.DictReader(open(e1_csv)))
    f1 = flags(e1, launchers, system_filter)
    # 광고 단위: 광고 하나에 RESUMED가 여러 번 남아도(화면 꺼짐) 하나라도 세면 잡은 것
    near = lambda s: [i for i in f1 if 0 <= int(e1[i]["timeStamp"]) - s <= 2000 and "bgad" in e1[i]["package"]]
    pos = [s for s in shows if near(s)]
    tp = sum(any(not f1[i] for i in near(s)) for s in pos)
    e2 = list(csv.DictReader(open(e2_csv)))
    f2 = flags(e2, launchers, system_filter)
    per = {}
    for w in csv.DictReader(open(windows_csv)):
        s, en = int(w["start"]), int(w["end"])
        for i in f2:
            if s <= int(e2[i]["timeStamp"]) <= en:
                d = per.setdefault(w["label"], [0, 0, {}])
                d[0] += 1
                if not f2[i]:
                    d[1] += 1
                    k = e2[i]["package"].split(".")[-1] + "/" + e2[i]["class"].split(".")[-1]
                    d[2][k] = d[2].get(k, 0) + 1
    return tp, len(pos), len(shows), per


def _check():
    L = {"ad.app": {"resolved": "ad.app.Main", "system": False}, "launcher": {"resolved": "", "system": True}}
    ev = lambda t, typ, pkg, cls="": {"timeStamp": str(t), "type": typ, "package": pkg, "class": cls}
    s = [ev(0, RESUMED, "launcher"), ev(1000, RESUMED, "ad.app", "ad.app.AdActivity"),       # 광고 → 셈
         ev(2000, PAUSED, "ad.app", "ad.app.AdActivity"), ev(2000, RESUMED, "ad.app", "ad.app.Other"),  # 같은 앱 안 전환 R2
         ev(3000, RESUMED, "launcher"), ev(4000, RESUMED, "ad.app", "ad.app.Main"),           # 아이콘 실행 R5
         ev(5000, RESUMED, "launcher"), ev(5900, USER_INTERACTION, "ad.app"), ev(6000, RESUMED, "ad.app", "ad.app.X")]  # 알림 탭 R3
    s += [ev(7000, RESUMED, "launcher"), ev(8000, RESUMED, "ad.app", "ad.app.Main"), ev(9000, SCREEN_OFF, "android"),
          ev(9500, SCREEN_ON, "android"), ev(9600, RESUMED, "ad.app", "ad.app.AdActivity")]                   # 끄기 전 쓰던 앱의 광고 → 셈 (R4는 돌아온 게 같은 앱이라 막힘)
    f = flags(s, L)
    assert f[len(s) - 1] == [], f
    assert f[1] == [] and f[3] == ["R2"] and f[5] == ["R5"] and f[8] == ["R3"] and "R1" in f[0], f


if __name__ == "__main__":
    _check()
    a = [x for x in sys.argv[1:] if not x.startswith("--")]
    tp, npos, nshow, per = evaluate(load_launchers(a[0]), *a[1:5], system_filter="--no-system-filter" not in sys.argv)
    print(f"광고(E1 saw): 셈 {tp}/{npos} (SHOW {nshow})")
    n = sum(d[0] for d in per.values()); fp = sum(d[1] for d in per.values())
    for k, (cnt, bad, what) in per.items():
        print(f"  정상 {k:<14} RESUMED {cnt:>3}  광고로 셈 {bad:>3}  {what if what else ''}")
    print(f"이벤트 단위 오탐률: {fp}/{n} = {fp / n:.1%}" if n else "정상 이벤트 없음")
