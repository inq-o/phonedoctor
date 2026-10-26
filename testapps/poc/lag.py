"""E4: 광고(SHOW)마다 15분 주기 작업이 그 광고의 RESUMED를 처음 본 시각까지의 지연.

사용: python3 testapps/poc/lag.py <truth.log> <work.csv>   → 광고별 지연(분), 못 본 광고 수, p50·p95
"""
import csv
import re
import sys


def lags(shows, work, window=2000):
    out = []
    for s in shows:
        runs = [int(r["runAt"]) for r in work if "bgad" in r["package"] and 0 <= int(r["timeStamp"]) - s <= window]
        out.append((min(runs) - s) / 60000 if runs else None)
    return out


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(q * (len(xs) - 1))))] if xs else None


if __name__ == "__main__":
    assert lags([0, 10**7], [{"runAt": "120000", "timeStamp": "20", "package": "x.bgad"}]) == [2.0, None]
    shows = [int(m[1]) for m in (re.search(r" SHOW mode=\w+ seq=\d+ t=(\d+)", l) for l in open(sys.argv[1])) if m]
    work = list(csv.DictReader(open(sys.argv[2]))) if open(sys.argv[2]).read(1) else []
    L = lags(shows, work)
    got = [x for x in L if x is not None]
    print("lags_min=" + " ".join("-" if x is None else f"{x:.1f}" for x in L))
    print(f"seen {len(got)}/{len(L)}  p50={pct(got, .5)}  p95={pct(got, .95)}  max={max(got) if got else None}")
