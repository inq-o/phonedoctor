"""실측 사용 기록을 rule_v0t로 처음부터 판정해 정답별로 집계한다 (오탐·광고 탐지 재계산용).

정답: 클래스 이름이 AdActivity로 끝나면 광고(재현 앱 bgad), 나머지는 정상. 정상 사용만 한 실측에 쓴다.
판정 시점 스냅샷은 실험 뒤 받은 목록이라 meta_validity=unverified다.

사용:
  python3 eval_real.py [--exported exported.csv] [--no-system-filter] <launchers.csv> <events.csv>...
  - launchers.csv·exported.csv·events.csv는 testapps/usageprobe의 LaunchersReceiver·DumpReceiver 출력
  - --exported가 없으면 모든 앱을 공개 화면 없음으로 본다(R7이 정상으로 바꾸지 못함)
"""
import collections
import csv
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import featx  # noqa: E402
import rule_v0  # noqa: E402


def main(argv):
    args, exported, system_filter = list(argv), {}, True
    if "--no-system-filter" in args:
        args.remove("--no-system-filter")
        system_filter = False
    if args and args[0] == "--exported":
        for r in csv.DictReader(open(args[1], encoding="utf-8")):
            exported.setdefault(r["package"], set()).add(r["activity"])
        args = args[2:]
    if len(args) < 2:
        sys.exit(__doc__)
    launchers = rule_v0.load_launchers(args[0])
    snap = {p: {"resolved": v["resolved"], "system": v["system"], "since": None, "exported": exported.get(p, set())}
            for p, v in launchers.items()}
    total, wrong = collections.Counter(), collections.Counter()
    for path in args[1:]:
        raw = list(csv.DictReader(open(path, encoding="utf-8")))
        if not raw:
            continue
        flat = sorted(([int(r["timeStamp"]), int(r["type"]), r["package"], r["class"]] for r in raw), key=lambda e: e[0])
        state = featx.init_state(flat[0][0], "install", wall=flat[0][0], elapsed=1_000_000, boot_count=1)
        rows = []
        for q in featx._single(flat, flat[0][0]):
            rows += featx.process_query(state, q, snap, system_filter)
        for r in rows:
            truth = "광고" if r["cls"].endswith("AdActivity") else "정상"
            out = r["rule_v0t"]["outcome"]
            total[(truth, out)] += 1
            if (truth == "정상") != (out == "NORMAL"):
                wrong[(truth, out, r["pkg"], r["cls"].split(".")[-1], ",".join(r["rule_v0t"]["unknown"]))] += 1
    print(f"시스템 앱 제외 {'켬' if system_filter else '끔'}, 공개 화면 목록 {'있음' if exported else '없음'}")
    for k in sorted(total):
        print(f"  정답 {k[0]} → 판정 {k[1]}: {total[k]}")
    for k, n in wrong.most_common(20):
        print(f"    {n:>4}  {k[0]}→{k[1]}  {k[2]}/{k[3]}" + (f"  모름 {k[4]}" if k[4] else ""))


if __name__ == "__main__":
    main(sys.argv[1:])
