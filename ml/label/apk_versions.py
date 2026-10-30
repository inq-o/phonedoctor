"""라벨 붙인 앱마다 특징을 뽑은 APK와 관찰한 APK가 같은 판인지 대조한다 (README §3).

- 특징 입력: ml/data/apk/<package>.apk. 그 SHA-256이 특징 파일(ml/data/apk_features.csv.gz)의 sha256과 같은지
- 관찰: 관찰한 base APK(ml/data/apk_split/<package>/<package>.apk 또는 <package>.apk)의 SHA-256과 versionCode.
  관찰 기록(ml/data/observe/<package>/meta.txt)의 apk_sha256과 지금 파일이 같은지. 기록이 없으면(2026-10-30 전 관찰)
  관찰 당시 APK와 같다고 확인할 수 없어 ok=unverified
- versionCode는 aapt2로 읽는다 (AAPT2 환경 변수, 없으면 Android SDK build-tools의 마지막 판)

ok가 yes가 아닌 앱이 있으면 exit 1. yes가 아닌 앱의 라벨은 설치 시점 분류기 평가에 쓰지 않는다
(no = 같은 판을 다시 받아 특징을 뽑을 때까지, unverified = SHA-256을 기록하며 다시 관찰할 때까지).
사용: python ml/label/apk_versions.py ml/label/test_v1_labels.csv
"""
import csv
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


def recorded_sha(pkg):
    """관찰 기록의 base APK SHA-256. 기록하기 전(2026-10-30 전) 관찰이면 None"""
    try:
        with open(f"{DATA}/observe/{pkg}/meta.txt", encoding="utf-8") as f:
            line = next((l for l in f if l.startswith("apk_sha256=")), "")
    except FileNotFoundError:
        return None
    pairs = dict(x.split(":", 1) for x in line.strip().split("=", 1)[-1].split(";") if ":" in x)
    return pairs.get(f"{pkg}.apk")


def compare(pkg, feature_sha, aapt2):
    feat = f"{DATA}/apk/{pkg}.apk"
    obs = f"{DATA}/apk_split/{pkg}/{pkg}.apk"
    if not os.path.exists(obs):
        obs = f"{DATA}/apk_split/{pkg}.apk"
    fs, os_, rec = sha256(feat), sha256(obs), recorded_sha(pkg)
    row = {"package": pkg, "feature_version": version_code(aapt2, feat), "observed_version": version_code(aapt2, obs),
           "feature_file": "same" if fs and fs == feature_sha else "diff",          # 특징 파일이 이 APK에서 나왔는지
           "observed_apk": "same" if fs and fs == os_ else "diff",                  # 관찰한 base APK가 특징 입력과 같은 파일인지
           "observation_record": "none" if rec is None else ("same" if rec == os_ else "diff")}
    files_same = (row["feature_version"] and row["feature_version"] == row["observed_version"]
                  and row["feature_file"] == row["observed_apk"] == "same")
    if not files_same or row["observation_record"] == "diff":
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
        pkgs = [row["package"] for row in csv.DictReader(f)]
    rows = [compare(p, feature_sha.get(p), aapt2) for p in pkgs]
    w = csv.DictWriter(sys.stdout, fieldnames=list(rows[0]) if rows else ["package"])
    w.writeheader()
    w.writerows(rows)
    bad = [x["package"] for x in rows if x["ok"] != "yes"]
    n = {k: sum(x["ok"] == k for x in rows) for k in ("yes", "no", "unverified")}
    print(f"{len(rows)}개: 확인 {n['yes']}, 판 다름 {n['no']}, 관찰 기록 없음 {n['unverified']}", file=sys.stderr)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
