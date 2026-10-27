"""E4: 액티비티 재개 확인(AdActivity RESUMED 로그)된 광고마다 15분 주기 작업이 처음 본 지연.

조회 여부와 30분 기한 준수를 **별도 축**으로 센다 (31분 뒤 조회는 조회 성공이면서 기한 실패).

- lag: 작업이 그 광고 이벤트를 window 안에서 처음 본 시각까지 분. 못 봤으면 None
- deadline(NFR-02 ≤30분, obs_end 기준):
  - within: 조회됨 & lag ≤ 30분
  - late:   조회됨 & lag > 30분 (조회 성공이지만 기한 실패)
  - missed: 미조회 & obs_end-SHOW ≥ 30분 (30분 관찰했는데 기록 안 됨 — 영구 미탐 아님)
  - pending: 미조회 & obs_end-SHOW < 30분 (30분 전에 관찰 종료 → 미결)
  - unknown: obs_end 모름 → 기한 판정 불가

obs_end는 **실제 실험 종료 시각**을 넘겨야 한다. 작업 실행 시각(runs.csv)에서 추정하지 않는다 —
작업이 멈춘 채 40분을 관찰한 경우와 관찰 자체가 일찍 끝난 경우가 섞이기 때문.

사용: python3 testapps/poc/lag.py <truth.log> <work.csv> [obs_end_ms | meta.txt(begin=/end= 포함)]
"""
import csv
import re
import sys

TARGET_MS = 30 * 60 * 1000


def classify(show_times, work, obs_end, window=2000, target_ms=TARGET_MS):
    out = []
    for s in show_times:
        runs = [int(r["runAt"]) for r in work if "bgad" in r["package"] and 0 <= int(r["timeStamp"]) - s <= window]
        if runs:
            lag = (min(runs) - s) / 60000
            out.append({"lag": lag, "deadline": "within" if (min(runs) - s) <= target_ms else "late"})
        elif obs_end is None:
            out.append({"lag": None, "deadline": "unknown"})
        elif obs_end - s >= target_ms:
            out.append({"lag": None, "deadline": "missed"})
        else:
            out.append({"lag": None, "deadline": "pending"})
    return out


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(q * (len(xs) - 1))))] if xs else None


def read_obs_end(arg):
    """정수 ms거나, begin=/end= 를 담은 meta.txt 경로."""
    if arg is None:
        return None
    if arg.isdigit():
        return int(arg)
    for line in open(arg):
        m = re.match(r"end=(\d+)", line.strip())
        if m:
            return int(m[1])
    return None


def _check():
    w = [{"runAt": "1860000", "timeStamp": "10", "package": "x.bgad"}]  # 31분 뒤 조회
    r = classify([0], w, obs_end=2400000)
    assert r[0] == {"lag": 31.0, "deadline": "late"}, r          # 조회 성공이지만 기한 실패
    r = classify([0], [{"runAt": "120000", "timeStamp": "10", "package": "x.bgad"}], obs_end=2400000)
    assert r[0] == {"lag": 2.0, "deadline": "within"}, r
    # 작업 멈춘 채 40분 관찰 → missed (미결 아님)
    assert classify([0], [], obs_end=2400000)[0]["deadline"] == "missed"
    # 관찰 30분 전 종료 → pending
    assert classify([0], [], obs_end=600000)[0]["deadline"] == "pending"
    # obs_end 모름 → unknown
    assert classify([0], [], obs_end=None)[0]["deadline"] == "unknown"


if __name__ == "__main__":
    _check()
    show_t = {m[1]: int(m[2]) for m in (re.search(r" SHOW mode=\w+ seq=(\d+) t=(\d+)", l) for l in open(sys.argv[1])) if m}
    displayed_seq = {m[1] for m in (re.search(r" RESUMED mode=\w+ seq=(\d+) t=\d+", l) for l in open(sys.argv[1])) if m}
    shows = [show_t[seq] for seq in sorted(displayed_seq, key=int) if seq in show_t]
    work = list(csv.DictReader(open(sys.argv[2]))) if open(sys.argv[2]).read(1) else []
    obs_end = read_obs_end(sys.argv[3]) if len(sys.argv) > 3 else None
    cls = classify(shows, work, obs_end)
    seen = [c["lag"] for c in cls if c["lag"] is not None]
    cnt = {k: sum(1 for c in cls if c["deadline"] == k) for k in ("within", "late", "missed", "pending", "unknown")}
    print("per_ad(lag/deadline)=" + " ".join(
        (f"{c['lag']:.1f}" if c["lag"] is not None else "-") + "/" + c["deadline"][0] for c in cls))
    print(f"액티비티 재개 확인 {len(cls)}건 · 조회됨 {len(seen)} (미조회 {len(cls)-len(seen)})")
    print(f"30분 기한: 준수 {cnt['within']} · 위반 {cnt['late']+cnt['missed']} (늦게 조회 {cnt['late']}, 30분내 미조회 {cnt['missed']}) · 미결 {cnt['pending']} · 종료불명 {cnt['unknown']}")
    if seen:
        print(f"조회된 표본의 조건부 지연(분): p50={pct(seen,.5):.1f} p95={pct(seen,.95):.1f} max={max(seen):.1f}")
    print("주의: 조회된 표본만의 통계. 미결·종료불명은 성공도 영구 누락도 아님. obs_end는 실제 실험 종료 시각")
