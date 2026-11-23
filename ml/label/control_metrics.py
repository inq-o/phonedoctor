"""T027 대조군 결과 집계. 같은 테스트셋을 시티즌코난·Play Protect로 검사한 기록에서 앱 단위 Recall·FPR을 낸다.

사용: python ml/label/control_metrics.py ml/label/test_v1_labels.csv ml/label/test_v1.csv ml/label/control_v1.csv

정답은 라벨 CSV(README §3)에서 adware와 확정 normal(weak 아님)만 쓴다. review·excluded·weak normal은 분모에서 뺀다.
대조군 결과는 control_group.md §4 형식. flagged만 "잡음"으로 세고, source_only(출처를 알 수 없는 앱 같은
설치 경로 경고만)는 잡지 않은 것으로 센다. not_scanned는 그 도구의 분모에서 빼고 개수를 따로 적는다.
"""
import csv
import sys
from collections import defaultdict
from datetime import date

HEADER = ["package", "tool", "tool_version", "result", "warning", "scanned"]
TOOLS = {"citizen_conan", "play_protect"}
RESULTS = {"flagged", "source_only", "clean", "not_scanned"}


def errors(rows, sample):
    out = []
    if not rows or rows[0] != HEADER:
        return [f"헤더가 {HEADER}가 아님: {rows[0] if rows else '빈 파일'}"]
    seen = set()
    for n, r in enumerate(rows[1:], start=2):
        if len(r) != len(HEADER):
            out.append(f"{n}행: 열 {len(r)}개 (쉼표가 든 경고 문구는 따옴표로 감쌀 것)")
            continue
        pkg, tool, version, result, warning, scanned = r
        if (pkg, tool) in seen:
            out.append(f"{n}행: {pkg}·{tool} 중복")
        seen.add((pkg, tool))
        if pkg not in sample:
            out.append(f"{n}행: {pkg}는 표본에 없음")
        if tool not in TOOLS:
            out.append(f"{n}행: tool {tool!r} 허용 안 됨")
        if not version.strip():
            out.append(f"{n}행: tool_version 비어 있음")
        if result not in RESULTS:
            out.append(f"{n}행: result {result!r} 허용 안 됨")
        if result in ("flagged", "source_only") and not warning.strip():
            out.append(f"{n}행: {result}인데 경고 문구가 없음")
        if result == "clean" and warning.strip():
            out.append(f"{n}행: clean인데 경고 문구가 있음")
        try:
            date.fromisoformat(scanned)
        except ValueError:
            out.append(f"{n}행: scanned {scanned!r} 날짜 아님")
    return out


def truth(label_rows):
    """패키지 → True(adware) / False(확정 normal). 나머지는 빼고"""
    out = {}
    for r in label_rows:
        if r["label"] == "adware":
            out[r["package"]] = True
        elif r["label"] == "normal" and r["confidence"] != "weak":
            out[r["package"]] = False
    return out


def metrics(answers, rows):
    """도구별 {recall: (잡은 adware, adware), fpr: (잡은 normal, normal), not_scanned: n}"""
    out = defaultdict(lambda: {"recall": [0, 0], "fpr": [0, 0], "not_scanned": 0})
    for r in rows:
        if r["package"] not in answers:
            continue
        m = out[r["tool"]]
        if r["result"] == "not_scanned":
            m["not_scanned"] += 1
            continue
        key = "recall" if answers[r["package"]] else "fpr"
        m[key][1] += 1
        if r["result"] == "flagged":
            m[key][0] += 1
    return {t: {k: tuple(v) if isinstance(v, list) else v for k, v in m.items()} for t, m in out.items()}


def ratio(pair):
    hit, total = pair
    return f"{hit}/{total} = {hit / total:.1%}" if total else f"{hit}/0 = -"


def main(labels_path, sample_path, control_path):
    with open(labels_path, encoding="utf-8") as f:
        answers = truth(list(csv.DictReader(f)))
    with open(sample_path, encoding="utf-8") as f:
        sample = {r["package"] for r in csv.DictReader(f)}
    with open(control_path, encoding="utf-8") as f:
        raw = list(csv.reader(f))
    errs = errors(raw, sample)
    for e in errs:
        print(e)
    if errs:
        sys.exit(f"{len(errs)}건 오류")
    rows = [dict(zip(HEADER, r)) for r in raw[1:]]
    done = {(r["package"], r["tool"]) for r in rows}
    missing = {t: [p for p in answers if (p, t) not in done] for t in TOOLS}
    for tool, m in sorted(metrics(answers, rows).items()):
        print(f"{tool}: Recall {ratio(m['recall'])}, 앱 단위 FPR {ratio(m['fpr'])}, 검사 못 함 {m['not_scanned']}")
    for tool, pkgs in sorted(missing.items()):
        if pkgs:
            print(f"{tool}: 기록 없는 정답 앱 {len(pkgs)}개 (예: {', '.join(sorted(pkgs)[:3])})")


def _check():
    ok = ["p1", "play_protect", "2026.11", "clean", "", "2026-11-24"]
    assert errors([HEADER, ok], {"p1"}) == []
    assert errors([HEADER, ok, ok], {"p1"})  # 중복
    assert errors([HEADER, ["p1", "play_protect", "", "clean", "", "2026-11-24"]], {"p1"})  # 판 없음
    assert errors([HEADER, ["p1", "citizen_conan", "1", "flagged", "", "2026-11-24"]], {"p1"})  # 문구 없음
    assert errors([HEADER, ["p1", "citizen_conan", "1", "clean", "악성", "2026-11-24"]], {"p1"})
    assert errors([HEADER, ["p1", "other", "1", "clean", "", "2026-11-24"]], {"p1"})
    labels = [
        {"package": "a1", "label": "adware", "confidence": ""},
        {"package": "a2", "label": "adware", "confidence": ""},
        {"package": "n1", "label": "normal", "confidence": ""},
        {"package": "n2", "label": "normal", "confidence": "weak"},  # 분모 제외
        {"package": "r1", "label": "review", "confidence": ""},  # 분모 제외
    ]
    answers = truth(labels)
    assert answers == {"a1": True, "a2": True, "n1": False}
    rows = [
        {"package": "a1", "tool": "citizen_conan", "result": "flagged"},
        {"package": "a2", "tool": "citizen_conan", "result": "source_only"},  # 설치 경로 경고는 못 잡은 것
        {"package": "n1", "tool": "citizen_conan", "result": "flagged"},
        {"package": "n2", "tool": "citizen_conan", "result": "flagged"},
        {"package": "a1", "tool": "play_protect", "result": "not_scanned"},
    ]
    m = metrics(answers, rows)
    assert m["citizen_conan"] == {"recall": (1, 2), "fpr": (1, 1), "not_scanned": 0}
    assert m["play_protect"] == {"recall": (0, 0), "fpr": (0, 0), "not_scanned": 1}
    assert ratio((0, 0)) == "0/0 = -"


if __name__ == "__main__":
    if len(sys.argv) == 4:
        main(sys.argv[1], sys.argv[2], sys.argv[3])
    else:
        _check()
        print("자체 검사 통과")
