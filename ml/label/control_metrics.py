"""T027 대조군 결과 집계. 같은 테스트셋을 시티즌코난·Play Protect로 검사한 기록에서 앱 단위 Recall·FPR을 낸다.

사용 (control_group.md §5):
  python ml/label/apk_versions.py ml/label/test_v1_labels.csv > versions.csv || true
  python ml/label/control_metrics.py ml/label/test_v1_labels.csv ml/label/test_v1.csv versions.csv ml/label/control_v1.csv

정답은 라벨 CSV(README §3)에서 adware와 확정 normal(weak 아님)이면서, apk_versions.py 대조에서 ok가 yes인 앱만 쓴다
(관찰한 판과 특징을 뽑은 판이 같아야 분류기 결과와 비교할 수 있음). review·excluded·weak normal·ok가 yes 아닌 앱은 뺀다.
대조군 결과는 control_group.md §4 형식. flagged만 "잡음"으로 세고, source_only(설치 경로 경고만)는 잡지 않은 것으로 세되 개수를 따로 낸다.
not_scanned는 그 도구의 분모에서 빼고 개수를 따로 낸다. 정답 앱 중 기록이 빠진 앱이 있으면 수치를 내지 않고 멈춘다(도구마다 앱 집합이 달라지지 않게).
"""
import csv
import sys
from collections import defaultdict
from datetime import date

from check_labels import errors as label_errors

HEADER = ["package", "tool", "tool_version", "device", "batch", "result", "warning", "scanned"]
TOOLS = ("citizen_conan", "play_protect")
RESULTS = {"flagged", "source_only", "clean", "not_scanned"}
VERSION_OK = {"yes", "no", "unverified"}
MAX_SPAN_DAYS = 3  # 한 도구는 이 기간 안에 다 잰다 (서버 쪽 정보가 바뀔 수 있음)


def errors(rows, sample):
    out = []
    if not rows or rows[0] != HEADER:
        return [f"헤더가 {HEADER}가 아님: {rows[0] if rows else '빈 파일'}"]
    seen = set()
    versions = defaultdict(set)
    days = defaultdict(set)
    for n, r in enumerate(rows[1:], start=2):
        if len(r) != len(HEADER):
            out.append(f"{n}행: 열 {len(r)}개 (쉼표가 든 경고 문구는 따옴표로 감쌀 것)")
            continue
        pkg, tool, version, device, batch, result, warning, scanned = r
        if (pkg, tool) in seen:
            out.append(f"{n}행: {pkg}·{tool} 중복")
        seen.add((pkg, tool))
        versions[tool].add((version, device))
        if pkg not in sample:
            out.append(f"{n}행: {pkg}는 표본에 없음")
        if tool not in TOOLS:
            out.append(f"{n}행: tool {tool!r} 허용 안 됨")
        if not version.strip() or not device.strip():
            out.append(f"{n}행: tool_version·device 비어 있음")
        if not batch.isdigit():
            out.append(f"{n}행: batch {batch!r}는 묶음 번호(숫자)여야 함")
        if result not in RESULTS:
            out.append(f"{n}행: result {result!r} 허용 안 됨")
        if result in ("flagged", "source_only", "not_scanned") and not warning.strip():
            out.append(f"{n}행: {result}인데 문구(검사 못 한 이유)가 없음")
        if result == "clean" and warning.strip():
            out.append(f"{n}행: clean인데 경고 문구가 있음")
        try:
            if len(scanned) != 10:
                raise ValueError
            days[tool].add(date.fromisoformat(scanned))
        except ValueError:
            out.append(f"{n}행: scanned {scanned!r}는 YYYY-MM-DD가 아님")
    for tool, vs in versions.items():
        if len(vs) > 1:
            out.append(f"{tool}: 판·기기가 섞임 {sorted(vs)} (한 판·한 기기로 다시 잴 것)")
    for tool, ds in days.items():
        if (max(ds) - min(ds)).days > MAX_SPAN_DAYS - 1:
            out.append(f"{tool}: 검사 날짜가 {min(ds)}~{max(ds)}로 {MAX_SPAN_DAYS}일을 넘음 (한 번에 다시 잴 것)")
    return out


def version_errors(rows):
    """apk_versions.py 결과: package·ok 열, 앱마다 한 줄, ok는 yes/no/unverified"""
    if not rows or not {"package", "ok"} <= set(rows[0]):
        return ["판 대조 결과에 package·ok 열이 없음"]
    out, seen = [], set()
    head = rows[0]
    for n, r in enumerate(rows[1:], start=2):
        row = dict(zip(head, r))
        if row.get("package", "") in seen:
            out.append(f"판 대조 {n}행: {row.get('package')} 중복")
        seen.add(row.get("package", ""))
        if row.get("ok") not in VERSION_OK:
            out.append(f"판 대조 {n}행: ok {row.get('ok')!r} 허용 안 됨")
    return out


def date_ranges(rows):
    ds = defaultdict(list)
    for r in rows:
        ds[r["tool"]].append(r["scanned"])
    return {t: (min(v), max(v)) for t, v in ds.items()}


def truth(label_rows, version_rows):
    """패키지 → True(adware) / False(확정 normal). 판 대조(ok=yes)를 통과하지 못한 앱은 뺀다. (정답, 판 때문에 뺀 수)"""
    verified = {r["package"] for r in version_rows if r["ok"] == "yes"}
    out, dropped = {}, 0
    for r in label_rows:
        if r["label"] == "adware" or (r["label"] == "normal" and r["confidence"] != "weak"):
            if r["package"] in verified:
                out[r["package"]] = r["label"] == "adware"
            else:
                dropped += 1
    return out, dropped


def metrics(answers, rows):
    """도구별 {recall: (잡은 adware, adware), fpr: (잡은 normal, normal), not_scanned: n, source_only: n}"""
    out = defaultdict(lambda: {"recall": [0, 0], "fpr": [0, 0], "not_scanned": 0, "source_only": 0})
    for r in rows:
        if r["package"] not in answers:
            continue
        m = out[r["tool"]]
        if r["result"] == "not_scanned":
            m["not_scanned"] += 1
            continue
        if r["result"] == "source_only":
            m["source_only"] += 1
        key = "recall" if answers[r["package"]] else "fpr"
        m[key][1] += 1
        if r["result"] == "flagged":
            m[key][0] += 1
    return {t: {k: tuple(v) if isinstance(v, list) else v for k, v in m.items()} for t, m in out.items()}


def missing(answers, rows):
    done = {(r["package"], r["tool"]) for r in rows}
    return {t: sorted(p for p in answers if (p, t) not in done) for t in TOOLS}


def ratio(pair):
    hit, total = pair
    return f"{hit}/{total} = {hit / total:.1%}" if total else f"{hit}/0 = -"


def read_csv(path):
    with open(path, encoding="utf-8") as f:
        return list(csv.reader(f))


def main(labels_path, sample_path, versions_path, control_path):
    with open(sample_path, encoding="utf-8") as f:
        sample = {r["package"] for r in csv.DictReader(f)}
    label_raw = read_csv(labels_path)
    errs = label_errors(label_raw, sample)
    raw = read_csv(control_path)
    errs += errors(raw, sample)
    version_raw = read_csv(versions_path)
    errs += version_errors(version_raw)
    version_rows = [dict(zip(version_raw[0], r)) for r in version_raw[1:]] if version_raw else []
    labelled = {r[0] for r in label_raw[1:]}
    if {r["package"] for r in version_rows} != labelled:
        errs.append(f"판 대조 결과({versions_path})의 앱이 라벨 파일과 다름 (같은 라벨 파일로 apk_versions.py를 다시 돌릴 것)")
    for e in errs:
        print(e)
    if errs:
        sys.exit(f"{len(errs)}건 오류")
    answers, dropped = truth([dict(zip(label_raw[0], r)) for r in label_raw[1:]], version_rows)
    rows = [dict(zip(HEADER, r)) for r in raw[1:]]
    gaps = {t: p for t, p in missing(answers, rows).items() if p}
    if gaps:
        for tool, pkgs in sorted(gaps.items()):
            print(f"{tool}: 기록 없는 정답 앱 {len(pkgs)}개 (예: {', '.join(pkgs[:3])})")
        sys.exit("모든 정답 앱을 두 도구로 기록한 뒤 집계 (도구마다 분모가 달라지지 않게)")
    print(f"정답 앱 {len(answers)}개 (adware {sum(answers.values())}, normal {len(answers) - sum(answers.values())}), 판 대조 미통과로 뺀 라벨 {dropped}개")
    for tool, (a, b) in sorted(date_ranges(rows).items()):
        print(f"{tool}: 검사 날짜 {a}~{b}")
    for tool, m in sorted(metrics(answers, rows).items()):
        print(f"{tool}: Recall {ratio(m['recall'])}, 앱 단위 FPR {ratio(m['fpr'])}, "
              f"검사 못 함 {m['not_scanned']}, 설치 경로 경고만 {m['source_only']}")


def _check():
    ok = ["p1", "play_protect", "gms 26.1", "Control_API_35", "1", "clean", "", "2026-11-24"]
    assert errors([HEADER, ok], {"p1"}) == []
    assert errors([HEADER, ok, ok], {"p1"})  # 중복
    assert errors([HEADER, ok[:2] + [""] + ok[3:]], {"p1"})  # 판 없음
    assert errors([HEADER, ok[:5] + ["flagged", ""] + ok[7:]], {"p1"})  # 문구 없음
    assert errors([HEADER, ok[:5] + ["not_scanned", ""] + ok[7:]], {"p1"})  # 이유 없음
    assert errors([HEADER, ok[:5] + ["clean", "악성"] + ok[7:]], {"p1"})
    assert errors([HEADER, ok[:7] + ["20261124"]], {"p1"})  # 날짜 형식
    assert errors([HEADER, ok, ["p2"] + ok[1:2] + ["gms 26.2"] + ok[3:]], {"p1", "p2"})  # 판 섞임
    assert errors([HEADER, ok, ["p2"] + ok[1:7] + ["2026-11-26"]], {"p1", "p2"}) == []  # 3일 안
    assert errors([HEADER, ok, ["p2"] + ok[1:7] + ["2026-11-27"]], {"p1", "p2"})  # 3일 넘음
    assert version_errors([["package", "ok"], ["a", "yes"]]) == []
    assert version_errors([["package", "ok"], ["a", "no"], ["a", "yes"]])  # 중복
    assert version_errors([["package", "ok"], ["a", "maybe"]])
    assert version_errors([["package"], ["a"]])
    labels = [
        {"package": "a1", "label": "adware", "confidence": ""},
        {"package": "a2", "label": "adware", "confidence": ""},
        {"package": "a3", "label": "adware", "confidence": ""},  # 판 다름 → 제외
        {"package": "n1", "label": "normal", "confidence": ""},
        {"package": "n2", "label": "normal", "confidence": "weak"},  # 분모 제외
        {"package": "r1", "label": "review", "confidence": ""},  # 분모 제외
    ]
    versions = [{"package": p, "ok": "yes"} for p in ("a1", "a2", "n1", "n2", "r1")] + [{"package": "a3", "ok": "no"}]
    answers, dropped = truth(labels, versions)
    assert answers == {"a1": True, "a2": True, "n1": False} and dropped == 1
    rows = [
        {"package": "a1", "tool": "citizen_conan", "result": "flagged"},
        {"package": "a2", "tool": "citizen_conan", "result": "source_only"},  # 설치 경로 경고는 못 잡은 것
        {"package": "n1", "tool": "citizen_conan", "result": "flagged"},
        {"package": "n2", "tool": "citizen_conan", "result": "flagged"},
        {"package": "a1", "tool": "play_protect", "result": "not_scanned"},
    ]
    m = metrics(answers, rows)
    assert m["citizen_conan"] == {"recall": (1, 2), "fpr": (1, 1), "not_scanned": 0, "source_only": 1}
    assert m["play_protect"] == {"recall": (0, 0), "fpr": (0, 0), "not_scanned": 1, "source_only": 0}
    assert missing(answers, rows) == {"citizen_conan": [], "play_protect": ["a2", "n1"]}
    assert ratio((0, 0)) == "0/0 = -"


if __name__ == "__main__":
    if len(sys.argv) == 5:
        main(*sys.argv[1:5])
    else:
        _check()
        print("자체 검사 통과")
