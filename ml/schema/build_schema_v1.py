"""특징 스키마 v1(feature_schema_v1.json)을 만든다. 앱과 학습 파이프라인이 이 파일 하나를 같이 읽는다.

어휘 규칙
- 권한·인텐트: MH-1M에서 정상·adware·기타 악성 중 한 집단이라도 보유율 ≥ MIN_RATE인 열
- 보안상 꼭 봐야 하는 권한(FORCE_PERMISSIONS)은 드물어도 포함
- 광고 SDK: ml/rules/ad_sdk_tags.csv (컴포넌트 이름 접두어, 출처는 각 행에)
- 이름 패턴: NAME_KEYWORDS (앱 라벨·패키지명 부분 문자열)
이름 정규화는 MH-1M 추출 코드(Malware-Hunter/SF23-AMGenerator extraction.py)와 같게 맞춘다.

사용: python ml/schema/build_schema_v1.py ml/data/mh1m/vocab_stats.csv ml/schema/feature_schema_v1.json
"""
import csv
import json
import sys
from pathlib import Path

import pandas as pd

MIN_RATE = 0.001
FORCE_PERMISSIONS = [
    "system_alert_window", "request_install_packages", "install_packages", "delete_packages",
    "request_delete_packages", "bind_accessibility_service", "bind_device_admin",
    "bind_notification_listener_service", "query_all_packages", "package_usage_stats",
    "read_sms", "receive_sms", "send_sms", "read_call_log", "write_call_log", "process_outgoing_calls",
    "read_contacts", "get_accounts", "bind_vpn_service", "use_full_screen_intent",
]
AD_SDK_CSV = Path(__file__).resolve().parent.parent / "rules" / "ad_sdk_tags.csv"
# 🔶 가정: 클리너·부스터 장르 키워드. 직접 수집 데이터(T025)로 보정할 것
NAME_KEYWORDS = [
    "청소", "클리너", "클린", "부스터", "최적화", "정크", "배터리", "절전", "와이파이", "쿨러", "속도 향상",
    "cleaner", "clean", "booster", "boost", "optimizer", "junk", "battery", "wifi", "cooler", "speedup",
]


def load_ad_sdks(path=AD_SDK_CSV):
    with open(path, newline="", encoding="utf-8") as f:
        return [{"id": r["sdk_id"], "flag": r["flag"] == "1", "prefixes": r["prefixes"].split("|"), "example": r["example"]}
                for r in csv.DictReader(f)]


def ad_sdk_hits(components, sdks):
    """컴포넌트 이름(패키지 포함 전체 이름) 목록 → 들어 있는 광고 SDK id 집합"""
    return {s["id"] for s in sdks for c in components if c.startswith(tuple(s["prefixes"]))}


def name_pattern_hit(label, package):
    text = f"{label} {package}".lower()
    return any(k in text for k in NAME_KEYWORDS)


def build(stats, sdks):
    rate = stats[["benign_rate", "adware_rate", "other_malware_rate"]].max(axis=1)
    kept = set(stats.loc[rate >= MIN_RATE, "column"]) | {f"permissions::{p}" for p in FORCE_PERMISSIONS}
    known = set(stats["column"])
    missing = kept - known
    assert not missing, f"MH-1M에 없는 강제 권한: {missing}"
    vocab = lambda prefix: sorted(c.split("::", 1)[1] for c in kept if c.startswith(prefix))
    return {
        "featureSchemaVersion": 1,
        "source": "MH-1M (Figshare 28355897, CC BY 4.0) 보유율 통계, build_schema_v1.py",
        "normalization": {
            "rule": "문자열을 '.'로 나눈 마지막 조각을 소문자로 바꾼 뒤 vocab에 있으면 1",
            "example": "android.intent.action.BOOT_COMPLETED → boot_completed",
            "note": "MH-1M 추출 코드와 같은 규칙. 패키지가 달라도 마지막 조각이 같으면 같은 특징으로 본다(예: com.x.permission.INTERNET → internet)",
        },
        "groups": [
            {"name": "permissions", "type": "multi_hot", "vocab": vocab("permissions::"),
             "device": "PackageInfo.requestedPermissions", "pc": "androguard APK.get_permissions()", "mh1m": True},
            {"name": "intent_filters", "type": "multi_hot", "vocab": vocab("intents::"),
             "device": "base.apk의 AndroidManifest.xml을 읽어 activity·receiver·service의 intent-filter action·category 값",
             "pc": "androguard APK.get_intent_filters(activity|receiver|service, name)의 모든 값", "mh1m": True},
            {"name": "apk_size_mb_log1p", "type": "float", "device": "File(ApplicationInfo.sourceDir).length()",
             "pc": "APK 파일 크기", "mh1m": True, "mh1m_column": "VT_SIZE (base.apk가 아니라 제출 파일 크기)"},
            {"name": "ad_sdk", "type": "count+multi_hot", "vocab": sorted(s["id"] for s in sdks if s["flag"]),
             "count_over": [s["id"] for s in sdks],
             "rules": {s["id"]: s["prefixes"] for s in sdks},
             "match": "activity·service·receiver·provider 전체 이름이 접두어로 시작하면 해당 SDK. '.'으로 시작하는 상대 이름은 패키지명을 붙여 푼 뒤 비교",
             "device": "base.apk 매니페스트 컴포넌트 이름", "pc": "androguard get_activities·get_services·get_receivers·get_providers",
             "source": "ml/rules/ad_sdk_tags.csv", "mh1m": False},
            {"name": "component_counts", "type": "int4", "fields": ["activity", "service", "receiver", "provider"], "mh1m": False},
            {"name": "sdk_versions", "type": "int2", "fields": ["minSdk", "targetSdk"], "mh1m": False},
            {"name": "launcher_icon_missing", "type": "bool", "mh1m": False},
            {"name": "cleaner_name_pattern", "type": "bool", "keywords": NAME_KEYWORDS,
             "match": "앱 라벨 + ' ' + 패키지명을 소문자로 바꿔 키워드가 부분 문자열로 들어 있으면 1", "mh1m": False},
        ],
    }


def _check():
    stats = pd.DataFrame({
        "column": ["permissions::internet", "permissions::system_alert_window", "permissions::rare", "intents::boot_completed"]
                  + [f"permissions::{p}" for p in FORCE_PERMISSIONS if p != "system_alert_window"],
        "benign_rate": [0.9, 0.0, 0.0, 0.4] + [0.0] * (len(FORCE_PERMISSIONS) - 1),
        "adware_rate": [0.9, 0.0, 0.0, 0.3] + [0.0] * (len(FORCE_PERMISSIONS) - 1),
        "other_malware_rate": [0.9, 0.0, 0.0, 0.4] + [0.0] * (len(FORCE_PERMISSIONS) - 1),
    })
    sdks = load_ad_sdks()
    s = build(stats, sdks)
    perms = s["groups"][0]["vocab"]
    assert "internet" in perms and "system_alert_window" in perms and "rare" not in perms
    assert s["groups"][1]["vocab"] == ["boot_completed"]
    # 광고 SDK: 각 예시는 자기 SDK에만 걸려야 함 (접두어 겹침 방지)
    assert len({x["id"] for x in sdks}) == len(sdks) and sum(x["flag"] for x in sdks) == 12
    for x in sdks:
        assert ad_sdk_hits([x["example"]], sdks) == {x["id"]}, x["id"]
    not_ads = ["com.unity3d.player.UnityPlayerActivity", "com.facebook.FacebookActivity",
               "com.google.android.gms.common.api.GoogleApiActivity", "com.kakao.sdk.auth.AuthCodeHandlerActivity",
               "com.bytedance.sdk.component.SomeService", "com.amazon.identity.auth.device.AuthActivity"]
    assert ad_sdk_hits(not_ads, sdks) == set()
    assert name_pattern_hit("스마트 클리너", "com.x.y") and name_pattern_hit("Phone", "com.fast.booster")
    assert not name_pattern_hit("카카오톡", "com.kakao.talk")


if __name__ == "__main__":
    _check()
    schema = build(pd.read_csv(sys.argv[1]), load_ad_sdks())
    with open(sys.argv[2], "w", encoding="utf-8") as f:
        json.dump(schema, f, ensure_ascii=False, indent=2)
    g = {x["name"]: len(x.get("vocab", [])) for x in schema["groups"]}
    print(f"→ {sys.argv[2]}: permissions {g['permissions']}, intent_filters {g['intent_filters']}, "
          f"ad_sdk {g['ad_sdk']} (count {len(schema['groups'][3]['count_over'])}), name keywords {len(NAME_KEYWORDS)}")
