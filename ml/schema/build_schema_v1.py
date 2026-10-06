"""특징 스키마 v1(feature_schema_v1.json)을 만든다. 앱과 학습 파이프라인이 이 파일 하나를 같이 읽는다.

어휘 규칙
- 권한·인텐트: MH-1M에서 정상·adware·기타 악성 중 한 집단이라도 보유율 ≥ MIN_RATE인 열
- 보안상 꼭 봐야 하는 권한(FORCE_PERMISSIONS)은 드물어도 포함
이름 정규화는 MH-1M 추출 코드(Malware-Hunter/SF23-AMGenerator extraction.py)와 같게 맞춘다.

사용: python ml/schema/build_schema_v1.py ml/data/mh1m/vocab_stats.csv ml/schema/feature_schema_v1.json
"""
import json
import sys

import pandas as pd

MIN_RATE = 0.001
FORCE_PERMISSIONS = [
    "system_alert_window", "request_install_packages", "install_packages", "delete_packages",
    "request_delete_packages", "bind_accessibility_service", "bind_device_admin",
    "bind_notification_listener_service", "query_all_packages", "package_usage_stats",
    "read_sms", "receive_sms", "send_sms", "read_call_log", "write_call_log", "process_outgoing_calls",
    "read_contacts", "get_accounts", "bind_vpn_service", "use_full_screen_intent",
]


def build(stats):
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
            {"name": "ad_sdk", "type": "count+multi_hot", "vocab": [], "status": "T021에서 확정 (LibChecker-Rules 광고 태깅)", "mh1m": False},
            {"name": "component_counts", "type": "int4", "fields": ["activity", "service", "receiver", "provider"], "mh1m": False},
            {"name": "sdk_versions", "type": "int2", "fields": ["minSdk", "targetSdk"], "mh1m": False},
            {"name": "launcher_icon_missing", "type": "bool", "mh1m": False},
            {"name": "cleaner_name_pattern", "type": "bool", "status": "키워드 목록 T020 후속", "mh1m": False},
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
    s = build(stats)
    perms = s["groups"][0]["vocab"]
    assert "internet" in perms and "system_alert_window" in perms and "rare" not in perms
    assert s["groups"][1]["vocab"] == ["boot_completed"]


if __name__ == "__main__":
    _check()
    schema = build(pd.read_csv(sys.argv[1]))
    with open(sys.argv[2], "w") as f:
        json.dump(schema, f, ensure_ascii=False, indent=2)
    g = {x["name"]: len(x.get("vocab", [])) for x in schema["groups"]}
    print(f"→ {sys.argv[2]}: permissions {g['permissions']}, intent_filters {g['intent_filters']}")
