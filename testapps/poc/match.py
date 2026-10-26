"""bgad 정답 로그(SHOW t=)와 usageprobe CSV를 맞춰, 광고마다 사용 기록에 무엇이 남았는지 센다.

사용: python3 testapps/poc/match.py <truth.log> <events.csv> [창(ms)=2000]
"""
import csv
import re
import sys

BGAD = "io.github.inq_o.phonedoctor.testapps.bgad"


def shows(lines):
    return [(int(m[1]), int(m[2])) for m in (re.search(r" SHOW mode=\w+ seq=(\d+) t=(\d+)", l) for l in lines) if m]


def match(truth, events, window=2000):
    """광고 seq마다 (RESUMED까지 지연 ms 또는 None, 창 안 bgad 이벤트 종류들)"""
    out = []
    for seq, t in truth:
        near = [e for e in events if e["package"] == BGAD and t <= int(e["timeStamp"]) <= t + window]
        resumed = [int(e["timeStamp"]) - t for e in near if e["type"] == "1"]  # ACTIVITY_RESUMED (옛 이름 MOVE_TO_FOREGROUND)
        out.append((seq, resumed[0] if resumed else None, sorted({e["typeName"] or e["type"] for e in near})))
    return out


def main(truth_path, events_path, window=2000):
    truth = shows(open(truth_path, encoding="utf-8"))
    events = list(csv.DictReader(open(events_path, encoding="utf-8")))
    rows = match(truth, events, int(window))
    for seq, lag, kinds in rows:
        print(f"seq={seq} resumed_lag_ms={lag} kinds={';'.join(kinds)}")
    hit = sum(lag is not None for _, lag, _ in rows)
    print(f"RESUMED {hit}/{len(rows)}")


def _check():
    ev = [{"package": BGAD, "timeStamp": "1020", "typeName": "ACTIVITY_RESUMED", "type": "1"},
          {"package": "x", "timeStamp": "1030", "typeName": "ACTIVITY_RESUMED", "type": "1"}]
    assert match([(0, 1000), (1, 5000)], ev) == [(0, 20, ["ACTIVITY_RESUMED"]), (1, None, [])]
    assert shows(["10-26 I BGAD : SHOW mode=saw seq=3 t=99"]) == [(3, 99)]


if __name__ == "__main__":
    _check()
    main(*sys.argv[1:4])
