"""T026 테스트셋 표본 추출. 받은 APK 중 group별로 고정 시드 무작위 추출.

동결한 테스트셋은 덮어쓰지 않는다. 추가분은 새 버전 파일(test_v2.csv 등)로 만든다.

사용: python ml/label/sample_testset.py ml/collect/packages.csv ml/data/apk ml/label/test_v1.csv
"""
import csv
import random
import sys
from pathlib import Path

SEED = 20261012
QUOTA = {"grayzone": 100, "senior": 50}  # 광고 도배는 회색지대에 몰릴 것이라 많이 뽑음


def sample(rows, quota=QUOTA, seed=SEED):
    rng = random.Random(seed)
    picked = []
    for group, n in quota.items():
        pool = sorted(r["package"] for r in rows if r["group"] == group)
        picked += [(p, group) for p in rng.sample(pool, n)]
    return sorted(picked)


def main(packages_path, apk_dir, out_path):
    if Path(out_path).exists():
        sys.exit(f"{out_path} 이미 있음: 동결한 테스트셋은 덮어쓰지 않는다. 새 버전 파일명을 쓸 것")
    have = {p.stem for p in Path(apk_dir).glob("*.apk")}
    with open(packages_path, encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f) if r["package"] in have]
    picked = sample(rows)
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["package", "group"])
        w.writerows(picked)
    print(f"{len(picked)}개 -> {out_path} (seed {SEED}, 받은 APK {len(rows)}개 중)")


def _check():
    rows = [{"package": f"g{i}", "group": "grayzone"} for i in range(5)] + [{"package": "s0", "group": "senior"}]
    a = sample(rows, {"grayzone": 2, "senior": 1})
    assert a == sample(rows, {"grayzone": 2, "senior": 1})  # 같은 시드면 같은 표본
    assert len(a) == 3 and ("s0", "senior") in a


if __name__ == "__main__":
    _check()
    main(*sys.argv[1:4])
