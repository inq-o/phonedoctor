"""E4: 표시 확인된 광고(RESUMED 로그)마다 15분 주기 작업이 그 광고를 처음 본 시각까지의 지연.

각 광고를 세 가지로 나눈다 (미결 표본을 성공/영구누락으로 단정하지 않음).
- seen: 작업이 window 안에서 그 광고의 이벤트를 봄 → 지연(분) 기록
- missed_30m: 광고 발생 후 마지막 작업 실행까지 >= 30분인데 끝내 못 봄 → '30분 내 기록 실패'
- pending: 마지막 작업 실행까지 < 30분이라 30분 목표를 판정하기엔 관찰이 일찍 끝남 → 미결

읽힌 표본만의 p50·p95는 '조회된 표본의 조건부 통계'다. 전체 지연 분포·p95 목표 충족이 아니다.

사용: python3 testapps/poc/lag.py <truth.log> <work.csv>
"""
import csv
import re
import sys

TARGET_MS = 30 * 60 * 1000


def classify(show_times, work, obs_end, window=2000, target_ms=TARGET_MS):
    """show_times: 표시 확인된 광고의 SHOW 시각 목록(사용 기록 매칭 기준점).
    재현기 RESUMED 로그 시각은 onResume(=시스템 이벤트 직후)이라 창 시작점으로 쓰면 바로 앞 이벤트를 놓친다.
    obs_end: 마지막 작업 실행 시각(관찰 종료).
    반환: 광고마다 ('seen', 지연분) / ('missed_30m', None) / ('pending', None)."""
    out = []
    for s in show_times:
        runs = [int(r["runAt"]) for r in work if "bgad" in r["package"] and 0 <= int(r["timeStamp"]) - s <= window]
        if runs:
            out.append(("seen", (min(runs) - s) / 60000))
        elif obs_end - s >= target_ms:
            out.append(("missed_30m", None))      # 30분 넘게 관찰했는데 못 봄
        else:
            out.append(("pending", None))         # 관찰이 30분 전에 끝남 → 미결
    return out


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(q * (len(xs) - 1))))] if xs else None


def _check():
    w = [{"runAt": "120000", "timeStamp": "20", "package": "x.bgad"}]
    # s=0: 2분 뒤 봄. s=10^7: 못 봄인데 obs_end(2*10^7)까지 30분 넘음 → missed. s=끝 직전: 미결
    r = classify([0, 10**7, 19_000_000], w, obs_end=20_000_000)
    assert r[0] == ("seen", 2.0) and r[1][0] == "missed_30m" and r[2][0] == "pending", r


if __name__ == "__main__":
    _check()
    # 표시 확인(RESUMED) 된 광고만 분모로. SHOW만 있고 표시 안 된 것은 지연 평가에서 뺌.
    # 매칭 기준점은 같은 seq의 SHOW 시각 (RESUMED 시각이 아니라)
    show_t = {m[1]: int(m[2]) for m in (re.search(r" SHOW mode=\w+ seq=(\d+) t=(\d+)", l) for l in open(sys.argv[1])) if m}
    displayed_seq = {m[1] for m in (re.search(r" RESUMED mode=\w+ seq=(\d+) t=\d+", l) for l in open(sys.argv[1])) if m}
    shows = [show_t[seq] for seq in sorted(displayed_seq, key=int) if seq in show_t]
    work = list(csv.DictReader(open(sys.argv[2]))) if open(sys.argv[2]).read(1) else []
    runs = [int(r["runAt"]) for r in work if "bgad" in r["package"]]
    obs_end = max(runs) if runs else (max(shows) if shows else 0)
    cls = classify(shows, work, obs_end)
    seen = [lag for k, lag in cls if k == "seen"]
    n_missed = sum(1 for k, _ in cls if k == "missed_30m")
    n_pending = sum(1 for k, _ in cls if k == "pending")
    mark = lambda k, lag: f"{lag:.1f}" if k == "seen" else ("x" if k == "missed_30m" else "?")
    print("per_ad=" + " ".join(mark(k, lag) for k, lag in cls))
    print(f"표시 확인 {len(cls)}건: 조회됨 {len(seen)} · 30분내 실패 {n_missed} · 미결(관찰 일찍 끝남) {n_pending}")
    if seen:
        print(f"조회된 표본의 조건부 지연(분): p50={pct(seen, .5):.1f} p95={pct(seen, .95):.1f} max={max(seen):.1f}")
    print("주의: 조회된 표본만의 통계. 미결 표본은 성공도 영구 누락도 아님 — 후속 관측 필요")
