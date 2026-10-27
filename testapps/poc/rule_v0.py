"""증상 감지 전환 분류 규칙 v0 (FR-012, 실증 E3). ACTIVITY_RESUMED 하나를 '앱 밖 광고로 셀지' 판정한다.

조건 (모두 만족하면 광고로 셈):
  R1 시스템 앱이 아님 (spec: 판정 대상은 사용자 설치 앱). 목록에 없는 패키지는 사용자 앱으로 봄 (아이콘 숨긴 앱을 놓치지 않게)
  R2 같은 앱 안 전환이 아님 = 직전 RESUMED가 다른 패키지, 또는 그 뒤 화면이 꺼졌거나 그 앱의 직전 화면이
     2초보다 전에 멈춤 (앱 안 전환·앱 안 광고 R6은 직전 화면 PAUSED와 거의 동시에 RESUMED.
     화면을 끄기 전 쓰던 앱이 나중에 띄운 광고는 셈 — E1 화면 꺼짐에서 확인)
  R3 직전 3초 안에 그 앱의 USER_INTERACTION 없음 (알림 탭 등)
  R4 화면 켜짐·잠금 해제 3초 안에, 화면이 꺼지기 전 보던 화면(같은 앱·같은 액티비티)으로 돌아온 게 아님
     (앱이 스스로 화면을 켜고 다른 액티비티를 띄우면 셈)
  R5 런처 아이콘이 여는 액티비티(별칭이면 targetActivity, 아이콘 여러 개면 그중 하나)가 아님 (사용자가 아이콘으로 연 실행 제외)

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
        if system_filter and info is not None and info["system"]:
            failed.append("R1")
        if prev_pkg == pkg and last_off < prev_t and t - last_paused.get(pkg, -10**12) <= HANDOFF_MS:
            failed.append("R2")
        recent = [x for x in events[max(0, i - 50):i] if t - int(x["timeStamp"]) <= LOOKBACK_MS]
        if any(x["type"] == USER_INTERACTION and x["package"] == pkg for x in recent):
            failed.append("R3")
        if any(x["type"] in (SCREEN_ON, KEYGUARD_HIDDEN) for x in recent) and before_off == (pkg, e["class"]):
            failed.append("R4")
        if info and e["class"] in info["resolved"]:
            failed.append("R5")
        out[i] = failed
        prev_pkg, prev_t, prev_cls = pkg, t, e["class"]
    return out


def load_launchers(path):
    m = {}
    for r in csv.DictReader(open(path, encoding="utf-8")):
        d = m.setdefault(r["package"], {"resolved": set(), "system": r["system"] == "true"})
        if r["resolvedClass"]:
            d["resolved"].add(r["resolvedClass"])
    return m


def ad_metrics(truth_log, e1_csv, launchers, system_filter=True, window=2000):
    """광고 지표를 단위별로 나눠 센다. 분모를 섞지 않으려고 네 수를 따로 돌려준다.

    - shows: 표시 '시도'(재현기 SHOW 로그). 실제 노출과 같지 않음
    - displayed: 액티비티 재개 확인(재현기 AdActivity onResume 로그가 남은 광고 건). 생명주기 확인이지 화면 관찰은 아님. 광고 건 단위 분모
    - observed: displayed 중 SHOW 시각 기준 window 안에 사용 기록 ACTIVITY_RESUMED가 남은 광고 건 수
    - classified: observed 중 규칙이 광고로 센 건 수 (조건부 분류율의 분자)

    광고 1건에 RESUMED가 여러 개 남아도(화면 꺼짐) 1건으로 집계. SHOW만 있고 재개 확인(RESUMED)이
    없으면 displayed 분모에 넣지 않는다. 사용 기록 매칭은 SHOW 시각을 기준으로 함 — 재현기의 RESUMED
    로그 시각은 onResume(=시스템 이벤트 직후)이라 창 시작점으로 쓰면 바로 앞 이벤트를 놓친다."""
    shows = {m[1]: int(m[2]) for m in (re.search(r" SHOW mode=\w+ seq=(\d+) t=(\d+)", l) for l in open(truth_log)) if m}
    displayed_seq = {m[1] for m in (re.search(r" RESUMED mode=\w+ seq=(\d+) t=\d+", l) for l in open(truth_log)) if m}
    e1 = list(csv.DictReader(open(e1_csv)))
    f1 = flags(e1, launchers, system_filter)
    near = lambda s: [i for i in f1 if 0 <= int(e1[i]["timeStamp"]) - s <= window and "bgad" in e1[i]["package"]]
    displayed = [shows[seq] for seq in displayed_seq if seq in shows]  # 재개 확인된 광고의 SHOW 시각
    observed = [s for s in displayed if near(s)]
    classified = sum(any(not f1[i] for i in near(s)) for s in observed)
    return {"shows": len(shows), "displayed": len(displayed), "observed": len(observed), "classified": classified}


def evaluate(launchers, truth_log, e1_csv, windows_csv, e2_csv, system_filter=True):
    m = ad_metrics(truth_log, e1_csv, launchers, system_filter)
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
    return m, per


def _check():
    L = {"ad.app": {"resolved": {"ad.app.Main", "ad.app.Main2"}, "system": False}, "launcher": {"resolved": set(), "system": True}}
    ev = lambda t, typ, pkg, cls="": {"timeStamp": str(t), "type": typ, "package": pkg, "class": cls}
    s = [ev(0, RESUMED, "launcher"), ev(1000, RESUMED, "ad.app", "ad.app.AdActivity"),       # 광고 → 셈
         ev(2000, PAUSED, "ad.app", "ad.app.AdActivity"), ev(2000, RESUMED, "ad.app", "ad.app.Other"),  # 같은 앱 안 전환 R2
         ev(3000, RESUMED, "launcher"), ev(4000, RESUMED, "ad.app", "ad.app.Main"),           # 아이콘 실행 R5
         ev(5000, RESUMED, "launcher"), ev(5900, USER_INTERACTION, "ad.app"), ev(6000, RESUMED, "ad.app", "ad.app.X")]  # 알림 탭 R3
    s += [ev(7000, RESUMED, "launcher"), ev(8000, RESUMED, "ad.app", "ad.app.Main"), ev(9000, SCREEN_OFF, "android"),
          ev(9500, SCREEN_ON, "android"), ev(9600, RESUMED, "ad.app", "ad.app.AdActivity")]                   # 끄기 전 쓰던 앱의 광고 → 셈 (R4는 돌아온 게 같은 앱이라 막힘)
    s += [ev(10000, RESUMED, "launcher"), ev(11000, RESUMED, "ad.app", "ad.app.Main2"),        # 두 번째 아이콘 실행 R5
          ev(12000, RESUMED, "launcher"), ev(13000, RESUMED, "hidden.app", "hidden.app.Ad")]   # 목록에 없는 앱의 광고 → 셈
    f = flags(s, L)
    assert f[13] == [] and f[15] == ["R5"] and f[17] == [], f
    assert f[1] == [] and f[3] == ["R2"] and f[5] == ["R5"] and f[8] == ["R3"] and "R1" in f[0], f
    _check_metrics()


def _check_metrics():
    """광고 지표 분모 회귀 검사. 표시 시도(SHOW)·재개 확인(RESUMED)·이벤트 관측·분류를
    섞지 않는지 임시 파일로 확인한다."""
    import tempfile, os
    L = {"bgad.app": {"resolved": set(), "system": False}}
    def run(truth, ev_rows):
        td = tempfile.mkdtemp()
        tp, ep = os.path.join(td, "t.log"), os.path.join(td, "e.csv")
        open(tp, "w").write(truth)
        with open(ep, "w", newline="") as f:
            w = csv.writer(f); w.writerow(["timeStamp", "type", "package", "class"]); w.writerows(ev_rows)
        return ad_metrics(tp, ep, L)
    # 1) 재개 확인 2건 중 이벤트 1건만 남고 그 1건을 규칙이 잡음
    #    → 조건부 분류율 1/1, 광고 건 단위 탐지율 1/2. SHOW(3)는 재개 확인(2)과 다름
    truth = (" SHOW mode=saw seq=0 t=1000\n SHOW mode=saw seq=1 t=5000\n SHOW mode=saw seq=2 t=9000\n"
             " RESUMED mode=saw seq=0 t=1000\n RESUMED mode=saw seq=1 t=5000\n")
    m = run(truth, [["1010", RESUMED, "bgad.app", "Ad"]])  # seq0 SHOW(1000) 뒤 10ms에 이벤트
    assert m == {"shows": 3, "displayed": 2, "observed": 1, "classified": 1}, m
    # 2) 광고 1건에 RESUMED 이벤트가 여러 개 남아도 1건으로 집계
    truth2 = " SHOW mode=saw seq=0 t=1000\n RESUMED mode=saw seq=0 t=1000\n"
    m2 = run(truth2, [["1010", RESUMED, "bgad.app", "Ad"], ["1900", RESUMED, "bgad.app", "Ad"]])
    assert m2 == {"shows": 1, "displayed": 1, "observed": 1, "classified": 1}, m2
    # 3) SHOW만 있고 재개 확인(RESUMED) 없음 → displayed 분모에 안 들어감
    m3 = run(" SHOW mode=saw seq=0 t=1000\n", [["1010", RESUMED, "bgad.app", "Ad"]])
    assert m3 == {"shows": 1, "displayed": 0, "observed": 0, "classified": 0}, m3


if __name__ == "__main__":
    _check()
    a = [x for x in sys.argv[1:] if not x.startswith("--")]
    m, per = evaluate(load_launchers(a[0]), *a[1:5], system_filter="--no-system-filter" not in sys.argv)
    sh, disp, obs, cl = m["shows"], m["displayed"], m["observed"], m["classified"]
    # 분모를 섞지 않음: 표시 시도≠재개 확인≠이벤트 관측. 전체 탐지율이라고 부르지 않는다
    print(f"표시 시도(SHOW) {sh} · 액티비티 재개 확인(RESUMED) {disp}")
    print(f"  이벤트 관측률(재개 확인 중 사용 기록 이벤트 있음): {obs}/{disp}" + (f" = {obs/disp:.1%}" if disp else ""))
    print(f"  조건부 분류율(관측된 광고 중 규칙이 잡음): {cl}/{obs}" + (f" = {cl/obs:.1%}" if obs else ""))
    print(f"  광고 건 단위 탐지율(재개 확인 전체 중 잡음): {cl}/{disp}" + (f" = {cl/disp:.1%}" if disp else ""))
    print("  주의: 재현 앱 1종 반복 결과 — 앱 단위 재현율(G2)·대표 실제 앱 탐지율은 미평가")
    n = sum(d[0] for d in per.values()); fp = sum(d[1] for d in per.values())
    for k, (cnt, bad, what) in per.items():
        print(f"  정상 {k:<14} RESUMED {cnt:>3}  광고로 셈 {bad:>3}  {what if what else ''}")
    print(f"이벤트 단위 오탐률: {fp}/{n} = {fp / n:.1%}" if n else "정상 이벤트 없음")
