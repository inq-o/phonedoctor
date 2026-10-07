"""APK에서 특징 스키마 v1 벡터를 뽑는다 (T022, PC 쪽).

기기 추출기(T023)와 같은 규칙이어야 한다 → specs/001-mvp/contracts/classifier.md §2.
어휘·광고 SDK 접두어·이름 키워드는 전부 ml/schema/feature_schema_v1.json에서 읽는다.

  parse(path)        APK → 원시 값(dict). 기기 쪽도 같은 모양을 만든다
  vectorize(raw, s)  원시 값 → (열 이름, 값) 목록. 열 순서는 스키마 groups 순서

사용: python ml/extract/extract.py ml/data/apk ml/data/apk_features.csv.gz
"""
import csv
import gzip
import hashlib
import json
import math
import sys
from multiprocessing import Pool
from pathlib import Path

from androguard.core.apk import APK
from loguru import logger

SCHEMA = Path(__file__).resolve().parent.parent / "schema" / "feature_schema_v1.json"
COMPONENTS = ["activity", "service", "receiver", "provider"]


def load_schema(path=SCHEMA):
    with open(path, encoding="utf-8") as f:
        s = json.load(f)
    return {g["name"]: g for g in s["groups"]} | {"version": s["featureSchemaVersion"]}


def norm(name):
    """MH-1M 규칙: '.'로 나눈 마지막 조각을 소문자로"""
    return name.rsplit(".", 1)[-1].lower()


def korean_label(a):
    """기기(한국어 로케일)와 같은 라벨. ko 리소스가 없으면 '@7F…' ID가 나오므로 기본 라벨로"""
    ko = a.get_app_name(locale="ko")
    return ko if ko and not ko.startswith("@") else (a.get_app_name() or "")


def parse(path):
    a = APK(str(path))
    if not a.is_valid_APK():  # 받는 중이거나 깨진 파일은 예외 없이 빈 값이 나온다
        raise ValueError("AndroidManifest.xml 없음")
    pkg = a.get_package()
    resolve = lambda n: pkg + n if n.startswith(".") else n
    comps = {
        "activity": a.get_activities(), "service": a.get_services(),
        "receiver": a.get_receivers(), "provider": a.get_providers(),
    }
    intents = set()
    for kind in ("activity", "receiver", "service"):
        for name in comps[kind]:
            f = a.get_intent_filters(kind, name)
            intents.update(f.get("action", []) + f.get("category", []))
    return {
        "package": pkg,
        "label": korean_label(a),
        "permissions": sorted(a.get_permissions()),
        "intents": sorted(intents),
        "components": {k: sorted(resolve(n) for n in v) for k, v in comps.items()},
        "min_sdk": int(a.get_min_sdk_version() or 1),
        "target_sdk": int(a.get_effective_target_sdk_version()),
        "has_launcher": bool(a.get_main_activities()),
        "size_bytes": Path(path).stat().st_size,
    }


def vectorize(raw, s):
    perms = {norm(p) for p in raw["permissions"]}
    intents = {norm(i) for i in raw["intents"]}
    all_comps = [c for v in raw["components"].values() for c in v]
    rules = s["ad_sdk"]["rules"]
    hits = {sdk for sdk, prefixes in rules.items() if any(c.startswith(tuple(prefixes)) for c in all_comps)}
    text = f"{raw['label']} {raw['package']}".lower()
    cols = [(f"perm::{p}", int(p in perms)) for p in s["permissions"]["vocab"]]
    cols += [(f"intent::{i}", int(i in intents)) for i in s["intent_filters"]["vocab"]]
    cols += [("apk_size_mb_log1p", round(math.log1p(raw["size_bytes"] / 2**20), 6))]
    cols += [("ad_sdk_count", len(hits & set(s["ad_sdk"]["count_over"])))]
    cols += [(f"ad_sdk::{x}", int(x in hits)) for x in s["ad_sdk"]["vocab"]]
    cols += [(f"n_{k}", len(raw["components"][k])) for k in COMPONENTS]
    cols += [("min_sdk", raw["min_sdk"]), ("target_sdk", raw["target_sdk"])]
    cols += [("launcher_icon_missing", int(not raw["has_launcher"]))]
    cols += [("cleaner_name_pattern", int(any(k in text for k in s["cleaner_name_pattern"]["keywords"])))]
    return cols


def _row(path):
    logger.remove()  # androguard 로그 끄기 (워커마다)
    try:
        raw = parse(path)
    except Exception as e:  # 깨진 APK는 건너뛰고 기록
        return path, None, repr(e)
    sha = hashlib.sha256(Path(path).read_bytes()).hexdigest().upper()
    return path, (sha, raw), None


def main(apk_dir, out_path):
    s = load_schema()
    paths = sorted(Path(apk_dir).glob("*.apk"))
    ok = 0
    with gzip.open(out_path, "wt", encoding="utf-8", newline="") as f, Pool() as pool:
        w = csv.writer(f)
        for i, (path, res, err) in enumerate(pool.imap(_row, paths)):
            if err:
                print(f"skip {path.name}: {err}", file=sys.stderr)
                continue
            sha, raw = res
            cols = vectorize(raw, s)
            if ok == 0:
                w.writerow(["sha256", "package", "schema_version", *(c for c, _ in cols)])
            w.writerow([sha, raw["package"], s["version"], *(v for _, v in cols)])
            ok += 1
    if ok == 0:  # 헤더 없는 빈 파일을 남기지 않는다
        Path(out_path).unlink()
        sys.exit(f"성공한 APK 없음 ({len(paths)}개 시도)")
    print(f"{ok}/{len(paths)} APK -> {out_path}")


def _check():
    s = load_schema()
    raw = {
        "package": "com.x.junkclean", "label": "폰 청소",
        "permissions": ["android.permission.INTERNET", "android.permission.SYSTEM_ALERT_WINDOW", "com.x.permission.C2D"],
        "intents": ["android.intent.action.BOOT_COMPLETED", "android.intent.action.MAIN"],
        "components": {"activity": ["com.x.Main", "com.google.android.gms.ads.AdActivity"],
                       "service": [], "receiver": ["com.applovin.impl.Recv"], "provider": []},
        "min_sdk": 21, "target_sdk": 34, "has_launcher": False, "size_bytes": 2**20,
    }
    v = dict(vectorize(raw, s))
    assert v["perm::internet"] == 1 and v["perm::system_alert_window"] == 1 and v["perm::camera"] == 0
    assert v["intent::boot_completed"] == 1
    assert v["ad_sdk::admob"] == 1 and v["ad_sdk::applovin"] == 1 and v["ad_sdk_count"] == 2
    assert v["n_activity"] == 2 and v["launcher_icon_missing"] == 1 and v["cleaner_name_pattern"] == 1
    assert v["apk_size_mb_log1p"] == round(math.log1p(1), 6)
    n = len(s["permissions"]["vocab"]) + len(s["intent_filters"]["vocab"]) + 1 + 1 + len(s["ad_sdk"]["vocab"]) + 4 + 2 + 2
    assert len(vectorize(raw, s)) == n, n


if __name__ == "__main__":
    _check()
    main(*sys.argv[1:3])
