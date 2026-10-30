"""라벨 붙인 앱마다 특징을 뽑은 APK와 관찰한 APK가 같은 판인지 대조한다 (README §3).

- 특징 입력: ml/data/apk/<package>.apk. 그 SHA-256이 특징 파일(ml/data/apk_features.csv.gz)의 sha256과 같은지
- 관찰: 관찰한 base APK(ml/data/apk_split/<package>/<package>.apk 또는 <package>.apk)의 SHA-256과 versionCode.
- 관찰 기록(ml/data/observe/<package>/meta.txt): 라벨이 이 관찰에서 나왔는지(끝까지 마친 관찰이고, 관찰 날짜가 라벨의
  observed와 같음, 한국 시각 기준), apk_sha256에 적힌 분할 APK 세트 전체가 지금 파일과 같은지.
  SHA-256 기록이 없으면(2026-10-30 전 관찰) 관찰 당시 APK와 같다고 확인할 수 없어 ok=unverified
- versionCode는 aapt2로 읽는다 (AAPT2 환경 변수, 없으면 Android SDK build-tools의 마지막 판)

ok가 yes가 아닌 앱이 있으면 exit 1. yes가 아닌 앱의 라벨은 설치 시점 분류기 평가에 쓰지 않는다
(no = 같은 판을 다시 받아 특징을 뽑거나 다시 관찰해 라벨을 새로 붙일 때까지, unverified = SHA-256을 기록하며 다시 관찰할 때까지).
사용: python ml/label/apk_versions.py ml/label/test_v1_labels.csv
"""
import csv
import datetime
import glob
import gzip
import hashlib
import os
import re
import subprocess
import sys

DATA = "ml/data"


def sha256(path):
    if not os.path.exists(path):
        return None
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def version_code(aapt2, path):
    if not os.path.exists(path):
        return None
    out = subprocess.run([aapt2, "dump", "badging", path], capture_output=True, text=True).stdout
    m = re.search(r"versionCode='(\d+)'", out)
    return m.group(1) if m else None


def observed_set(pkg):
    """관찰에 쓰는 APK 세트 {파일명: SHA-256}. 분할 APK면 폴더 안 전부"""
    d = f"{DATA}/apk_split/{pkg}"
    paths = sorted(glob.glob(f"{d}/*.apk")) if os.path.isdir(d) else [f"{d}.apk"]
    return {os.path.basename(p): sha256(p) for p in paths if os.path.exists(p)}


def record(pkg, observed):
    """관찰 기록과 라벨 대조. none = SHA-256 기록 없음(2026-10-30 전 관찰), stale = 라벨이 이 관찰에서 나오지 않음, 그 밖엔 {파일명: SHA-256}"""
    d = f"{DATA}/observe/{pkg}"
    try:
        with open(f"{d}/meta.txt", encoding="utf-8") as f:
            meta = dict(l.rstrip("\n").split("=", 1) for l in f if "=" in l)
        with open(f"{d}/run.log", encoding="utf-8") as f:
            done = any("] done" in l for l in f)
    except FileNotFoundError:
        return "none"
    if "apk_sha256" not in meta:
        return "none"
    run = datetime.datetime.strptime(meta.get("date", ""), "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)
    run_day = run.astimezone(datetime.timezone(datetime.timedelta(hours=9))).date().isoformat()
    if not done or run_day != observed:   # 다시 관찰했거나 끝까지 못 마친 관찰 → 라벨을 새 증거로 다시 붙여야 함
        return "stale"
    return dict(x.split(":", 1) for x in meta["apk_sha256"].split(";") if ":" in x)


def compare(pkg, observed, feature_sha, aapt2):
    feat = f"{DATA}/apk/{pkg}.apk"
    obs_set = observed_set(pkg)
    obs = f"{DATA}/apk_split/{pkg}/{pkg}.apk" if os.path.isdir(f"{DATA}/apk_split/{pkg}") else f"{DATA}/apk_split/{pkg}.apk"
    fs, rec = sha256(feat), record(pkg, observed)
    row = {"package": pkg, "feature_version": version_code(aapt2, feat), "observed_version": version_code(aapt2, obs),
           "feature_file": "same" if fs and fs == feature_sha else "diff",          # 특징 파일이 이 APK에서 나왔는지
           "observed_apk": "same" if fs and fs == obs_set.get(f"{pkg}.apk") else "diff",   # 관찰한 base APK가 특징 입력과 같은 파일인지
           "observation_record": rec if isinstance(rec, str) else ("same" if rec == obs_set else "diff")}   # 분할 APK 세트 전체
    files_same = (row["feature_version"] and row["feature_version"] == row["observed_version"]
                  and row["feature_file"] == row["observed_apk"] == "same")
    if not files_same or row["observation_record"] in ("diff", "stale"):
        row["ok"] = "no"
    else:
        row["ok"] = "yes" if row["observation_record"] == "same" else "unverified"
    return row


def main(labels_path):
    sdk = os.path.expanduser(os.environ.get("ANDROID_HOME", "~/Library/Android/sdk"))
    aapt2 = os.environ.get("AAPT2") or (sorted(glob.glob(f"{sdk}/build-tools/*/aapt2")) or [None])[-1]
    if not aapt2:
        sys.exit("aapt2 없음: AAPT2=<경로>로 지정")
    with gzip.open(f"{DATA}/apk_features.csv.gz", "rt", encoding="utf-8") as f:
        r = csv.reader(f)
        head = next(r)
        si, pi = head.index("sha256"), head.index("package")
        feature_sha = {row[pi]: row[si].lower() for row in r}
    with open(labels_path, encoding="utf-8") as f:
        labels = [(row["package"], row["observed"]) for row in csv.DictReader(f)]
    rows = [compare(p, day, feature_sha.get(p), aapt2) for p, day in labels]
    w = csv.DictWriter(sys.stdout, fieldnames=list(rows[0]) if rows else ["package"])
    w.writeheader()
    w.writerows(rows)
    bad = [x["package"] for x in rows if x["ok"] != "yes"]
    n = {k: sum(x["ok"] == k for x in rows) for k in ("yes", "no", "unverified")}
    print(f"{len(rows)}개: 확인 {n['yes']}, 판 다름 {n['no']}, 관찰 기록 없음 {n['unverified']}", file=sys.stderr)
    return 1 if bad else 0


def _check():
    """가짜 APK로 관찰 기록 대조 확인 (aapt2 대신 파일 유무로 versionCode 흉내)"""
    import tempfile
    global DATA, version_code
    saved = DATA, version_code
    version_code = lambda aapt2, path: "1" if os.path.exists(path) else None
    h = lambda b: hashlib.sha256(b).hexdigest()
    p = "x.pkg"
    try:
        for split, date, done, has_sha, want in [(b"S", "2026-10-30T06:00:00Z", True, True, "yes"),
                                                 (b"T", "2026-10-30T06:00:00Z", True, True, "no"),        # split만 바뀜
                                                 (b"S", "2026-10-31T01:00:00Z", True, True, "no"),        # 다시 관찰함
                                                 (b"S", "2026-10-29T16:00:00Z", True, True, "yes"),       # 한국 시각으로 같은 날
                                                 (b"S", "2026-10-30T06:00:00Z", False, True, "no"),       # 끝까지 못 마침
                                                 (b"S", "2026-10-30T06:00:00Z", True, False, "unverified")]:
            DATA = tempfile.mkdtemp()
            for d in ("apk", f"apk_split/{p}", f"observe/{p}"):
                os.makedirs(f"{DATA}/{d}")
            for path, b in [(f"apk/{p}.apk", b"B"), (f"apk_split/{p}/{p}.apk", b"B"), (f"apk_split/{p}/{p}.config.ko.apk", split)]:
                with open(f"{DATA}/{path}", "wb") as f:
                    f.write(b)
            with open(f"{DATA}/observe/{p}/meta.txt", "w", encoding="utf-8") as f:
                f.write(f"date={date}\n" + (f"apk_sha256={p}.apk:{h(b'B')};{p}.config.ko.apk:{h(b'S')}\n" if has_sha else ""))
            with open(f"{DATA}/observe/{p}/run.log", "w", encoding="utf-8") as f:
                f.write("[00:00:00] done\n" if done else "[00:00:00] aborted\n")
            got = compare(p, "2026-10-30", h(b"B"), "aapt2")["ok"]
            assert got == want, (split, date, done, has_sha, got)
    finally:
        DATA, version_code = saved


if __name__ == "__main__":
    _check()
    sys.exit(main(sys.argv[1]))
