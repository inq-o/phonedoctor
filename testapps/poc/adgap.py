"""재현기 광고의 실측 간격·유지 시간을 계측한다.

SHOW는 AdReceiver가 Activity 시작을 '시도'하기 전 로그, RESUMED는 실제 재개다. 둘을 섞지 않는다.
- 시도 간격(SHOW): 이웃 SHOW 사이 시간. 알람 빈도 제한 확인용
- 재개 간격(RESUMED): 이웃 RESUMED 사이 시간. 실제로 화면에 올라온 간격
- 유지 시간: 각 RESUMED를 그 뒤 첫 PAUSED와 순서대로 짝지은 구간(재개→중지). 생명주기 기반 **대용 지표**이며
  다중 창에서 RESUMED가 창 포커스를 보장하지 않으므로 실제 화면 노출 시간으로 단정하지 않는다.
  같은 seq가 중지 뒤 다시 재개되면 각 재개를 각각 짝짓는다(마지막 값으로 덮지 않음).

사용: python3 testapps/poc/adgap.py <truth.log>   (SHOW·RESUMED·PAUSED 포함)
"""
import re
import sys


def events(lines):
    """시간순 (kind, seq, t) 목록. kind ∈ {SHOW, RESUMED, PAUSED}."""
    out = []
    for l in lines:
        m = re.search(r" (SHOW|RESUMED|PAUSED) mode=\w+ seq=(\d+) t=(\d+)", l)
        if m:
            out.append((m[1], m[2], int(m[3])))
    return sorted(out, key=lambda e: e[2])


def intervals(evs, kind):
    ts = [t for k, _, t in evs if k == kind]
    return [(b - a) / 1000 for a, b in zip(ts, ts[1:])]


def holds(evs):
    """RESUMED를 그 뒤 첫 PAUSED와 순서대로 짝지은 유지 구간(초). 짝 없는 RESUMED는 버림."""
    out = []
    pending = None  # 짝을 기다리는 RESUMED 시각
    for k, _, t in evs:
        if k == "RESUMED":
            pending = t
        elif k == "PAUSED" and pending is not None:
            out.append((t - pending) / 1000)
            pending = None
    return out


def _check():
    evs = events([
        " SHOW mode=saw seq=0 t=1000", " RESUMED mode=saw seq=0 t=4000", " PAUSED mode=saw seq=0 t=6000",
        " SHOW mode=saw seq=1 t=7000", " RESUMED mode=saw seq=1 t=12000", " PAUSED mode=saw seq=1 t=14000",
    ])
    assert intervals(evs, "SHOW") == [6.0], intervals(evs, "SHOW")        # 시도 간격
    assert intervals(evs, "RESUMED") == [8.0], intervals(evs, "RESUMED")  # 재개 간격
    assert holds(evs) == [2.0, 2.0], holds(evs)                            # 재개→중지 (시작 지연 제외)
    # 같은 seq 재개 2번 → 각각 짝지음 (마지막 값으로 안 덮음)
    evs2 = events([
        " RESUMED mode=saw seq=0 t=0", " PAUSED mode=saw seq=0 t=1900",
        " RESUMED mode=saw seq=0 t=5000", " PAUSED mode=saw seq=0 t=7000",
    ])
    assert holds(evs2) == [1.9, 2.0], holds(evs2)


def stat(xs):
    return f"n={len(xs)} min={min(xs):.1f} avg={sum(xs)/len(xs):.1f} max={max(xs):.1f}" if xs else "없음"


if __name__ == "__main__":
    _check()
    evs = events(open(sys.argv[1]))
    nshow = sum(1 for k, _, _ in evs if k == "SHOW")
    nres = sum(1 for k, _, _ in evs if k == "RESUMED")
    print(f"SHOW {nshow} · RESUMED(재개 확인) {nres}")
    print(f"시도 간격(초, SHOW): {stat(intervals(evs, 'SHOW'))}")
    print(f"재개 간격(초, RESUMED): {stat(intervals(evs, 'RESUMED'))}")
    print(f"유지 시간(초, 재개→중지, 대용 지표): {stat(holds(evs))}")
    print("주의: 설정 intervalSec/holdSec이 아니라 로그 실측. 알람 빈도 제한으로 설정과 다를 수 있음. RESUMED는 창 포커스를 보장하지 않음")
