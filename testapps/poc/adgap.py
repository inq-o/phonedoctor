"""재현기 광고의 실제 표시 간격·유지 시간을 계측한다.

truth.log의 로그로 광고 1건의 실제 타이밍을 잰다 (설정한 intervalSec/holdSec이 아니라 실측):
- interval: 이웃한 SHOW seq 사이 시간 (알람 빈도 제한 때문에 설정값대로 안 될 수 있음)
- hold: 같은 seq의 SHOW→다음 PAUSED(화면에서 내려감)까지. PAUSED가 없으면 None

사용: python3 testapps/poc/adgap.py <truth.log>   (truth.log에 SHOW·RESUMED·PAUSED 포함)
"""
import re
import sys


def parse(lines):
    show, resumed, paused = {}, {}, {}
    for l in lines:
        m = re.search(r" (SHOW|RESUMED) mode=\w+ seq=(\d+) t=(\d+)", l)
        if m:
            (show if m[1] == "SHOW" else resumed)[m[2]] = int(m[3])
        p = re.search(r" PAUSED mode=\w+ seq=(\d+) t=(\d+)", l)
        if p:
            paused[p[1]] = int(p[2])
    return show, resumed, paused


def gaps(show):
    seqs = sorted(show, key=int)
    return [(show[b] - show[a]) / 1000 for a, b in zip(seqs, seqs[1:])]


def holds(show, paused):
    return {s: (paused[s] - show[s]) / 1000 for s in show if s in paused}


def _check():
    lines = [
        " SHOW mode=saw seq=0 t=1000", " RESUMED mode=saw seq=0 t=1020", " PAUSED mode=saw seq=0 t=4000",
        " SHOW mode=saw seq=1 t=6000", " PAUSED mode=saw seq=1 t=9000",
    ]
    sh, re_, pa = parse(lines)
    assert gaps(sh) == [5.0], gaps(sh)              # 설정 간격과 다를 수 있는 실측 간격
    assert holds(sh, pa) == {"0": 3.0, "1": 3.0}, holds(sh, pa)


if __name__ == "__main__":
    _check()
    sh, re_, pa = parse(open(sys.argv[1]))
    g = gaps(sh); h = list(holds(sh, pa).values())
    avg = lambda xs: sum(xs) / len(xs) if xs else None
    print(f"광고 {len(sh)}건 (표시 확인 {len(re_)})")
    print(f"실측 간격(초): n={len(g)} min={min(g):.1f} avg={avg(g):.1f} max={max(g):.1f}" if g else "간격 없음")
    print(f"실측 유지(초): n={len(h)} min={min(h):.1f} avg={avg(h):.1f} max={max(h):.1f}" if h else "PAUSED 없어 유지 시간 미측정")
    print("주의: 설정 intervalSec/holdSec이 아니라 로그 실측값. 알람 빈도 제한으로 설정과 다를 수 있음")
