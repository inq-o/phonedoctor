"""T026 라벨 CSV 형식 검사. 열 개수·허용값·날짜·패키지 중복·표본 소속을 확인한다.

사용: python ml/label/check_labels.py ml/label/test_v1_labels.csv ml/label/test_v1.csv
"""
import csv
import re
import sys
from datetime import date

HEADER = ["package", "label", "codes", "confidence", "evidence", "observed"]
LABELS = {"adware", "normal", "excluded", "review"}
CONFIDENCE = {"", "weak"}
CODES = re.compile(r"R[1-6]\??(;R[1-6]\??)*")  # ?는 해당 여부 불확실(review에서만)


def errors(rows, sample):
    out = []
    if not rows or rows[0] != HEADER:
        return [f"헤더가 {HEADER}가 아님: {rows[0] if rows else '빈 파일'}"]
    seen = set()
    for n, r in enumerate(rows[1:], start=2):
        if len(r) != len(HEADER):
            out.append(f"{n}행: 열 {len(r)}개 (쉼표가 든 근거는 따옴표로 감쌀 것)")
            continue
        pkg, label, codes, conf, evidence, observed = r
        if pkg in seen:
            out.append(f"{n}행: {pkg} 중복")
        seen.add(pkg)
        if pkg not in sample:
            out.append(f"{n}행: {pkg}는 표본에 없음")
        if label not in LABELS:
            out.append(f"{n}행: label {label!r} 허용 안 됨")
        if conf not in CONFIDENCE:
            out.append(f"{n}행: confidence {conf!r} 허용 안 됨")
        if label in ("adware", "review") and not CODES.fullmatch(codes):
            out.append(f"{n}행: {label}인데 codes {codes!r} 형식 아님")
        if label == "adware" and "?" in codes:
            out.append(f"{n}행: 불확실한 코드는 adware가 아니라 review")
        if label == "adware" and not set(codes.split(";")) - {"R6"}:
            out.append(f"{n}행: R6(앱 안 광고)만으로는 adware가 아님")
        if label == "normal" and codes not in ("", "R6"):
            out.append(f"{n}행: normal에는 R6 표시만 가능")
        if label == "excluded" and codes:
            out.append(f"{n}행: excluded인데 codes가 있음")
        if not evidence.strip():
            out.append(f"{n}행: evidence 비어 있음")
        try:
            date.fromisoformat(observed)
        except ValueError:
            out.append(f"{n}행: observed {observed!r} 날짜 아님")
    return out


def main(labels_path, sample_path):
    with open(sample_path, encoding="utf-8") as f:
        sample = {r["package"] for r in csv.DictReader(f)}
    with open(labels_path, encoding="utf-8") as f:
        rows = list(csv.reader(f))
    errs = errors(rows, sample)
    for e in errs:
        print(e)
    if errs:
        sys.exit(f"{len(errs)}건 오류")
    print(f"{len(rows) - 1}행 통과 (표본 {len(sample)}개 중)")


def _check():
    ok = ["p1", "normal", "", "weak", "a, b", "2026-10-12"]
    assert errors([HEADER, ok], {"p1"}) == []
    assert errors([HEADER, ok[:4] + ["a", " b", "2026-10-12"]], {"p1"})  # 인용 안 된 쉼표
    assert errors([HEADER, ["p1", "adware", "R6?", "", "x", "2026-10-12"]], {"p1"})
    assert errors([HEADER, ["p1", "review", "R1?;R2", "", "x", "2026-10-12"]], {"p1"}) == []
    assert errors([HEADER, ["p1", "adware", "R6", "", "x", "2026-10-12"]], {"p1"})  # 앱 안 광고만
    assert errors([HEADER, ["p1", "normal", "R6", "", "x", "2026-10-12"]], {"p1"}) == []
    assert errors([HEADER, ["p1", "normal", "R1", "", "x", "2026-10-12"]], {"p1"})
    assert errors([HEADER, ok, ok], {"p1"})  # 중복


if __name__ == "__main__":
    _check()
    main(*sys.argv[1:3])
