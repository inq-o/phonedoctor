# 온디바이스 판정기 계약 (Classifier Contract)

- 기기 앱(`core:classifier`)과 학습 파이프라인(`ml/`)이 **같은 입력·같은 출력**을 쓰기 위한 계약
- 핵심 규칙: **학습 특징 = 기기에서 뽑을 수 있는 특징**. 기기에서 못 뽑는 특징(API 호출 그래프·옵코드 등)은 학습에 쓰지 않음
- 모델 형식(GBDT vs MLP)은 미정(spec Q4). 이 계약은 형식과 무관
- **역할: 보조 경로** ([ADR-0003](../../../docs/adr/0003-detection-approach.md)). 설치 즉시(실행 전) 사전 경고와 사용 기록에 안 남는 오버레이형의 보조 신호(`OVERLAY_PERMISSION`). 앱 밖 광고의 주 감지는 사용 기록 증상 감지(spec US11, plan "증상 감지 설계")이며 이 계약 밖

## 1. 판정 구조 — 2층

```
          정적 특징 (매니페스트·서명·SDK)            런타임 신호 (기기 상태)
                    │                                        │
            [층 1] 학습 모델 (LiteRT)               [층 2] 규칙 (가중치 고정·공개)
                    │  p_static                              │  bonus
                    └──────────────┬─────────────────────────┘
                                   ▼
                score = clamp(p_static + Σbonus, 0, 1)
                level = SAFE | CAUTION | RISK  (thresholds from ModelRelease)
                reasons = 상위 3개 근거 코드
```

- **층 1**: 공개 데이터셋(MH-1M 등)과 직접 수집 APK로 학습 가능한 정적 특징만
- **층 2**: 데이터셋에 없는 기기 상태(기기 관리자 활성, 접근성 서비스 켜짐, 설치 출처 등). 학습이 아니라 규칙이라 **근거를 그대로 설명 가능**
- 층 2 가중치는 검증셋에서 앱 단위 FPR 5%를 넘지 않는 범위로 조정하고 `ml/rules/runtime_bonus.yaml`에 버전 관리

## 2. 입력: 특징 스키마 v1 (`featureSchemaVersion = 1`)

| 그룹 | 특징 | 형태 | 기기에서 얻는 법 | PC(학습)에서 얻는 법 |
|---|---|---|---|---|
| 권한 | v1 어휘 121개 (MH-1M 보유율 ≥0.1% + 보안 권한 강제 포함) | multi-hot | base.apk 매니페스트의 `uses-permission` (아래 변경 2026-11-24) | androguard `get_permissions()` |
| 인텐트 필터 | v1 어휘 91개. activity·receiver·service의 intent-filter **action·category 값** | multi-hot | **base.apk의 AndroidManifest.xml을 직접 파싱** (`ApplicationInfo.sourceDir`, 바이너리 XML) | androguard `get_intent_filters()` |
| 광고 SDK | 광고 SDK 수(23종 기준), 주요 SDK 12종 플래그 (AdMob, AppLovin, Pangle, Unity Ads, ironSource, Mintegral, Vungle, InMobi, Meta AN, 카울리, 애드핏, 모비온) | count + multi-hot | 매니페스트 컴포넌트 이름을 `ml/rules/ad_sdk_tags.csv` 접두어와 대조 | 같은 접두어를 androguard 컴포넌트 목록에 적용 |
| 컴포넌트 | activity·service·receiver·provider 수 | int ×4 | base.apk 매니페스트 (같은 이름이 둘이면 둘 다 셈) | androguard |
| 메타 | targetSdk, minSdk, APK 크기(MB, log) | int/float | base.apk 매니페스트 `uses-sdk`(targetSdk 없으면 minSdk), `sourceDir` 파일 크기 | androguard·파일 크기 |
| 아이콘 | 런처 아이콘 없음 | bool | 매니페스트에 꺼지지 않은(`enabled≠false`) activity·activity-alias 중 자기 아래(모든 깊이)에 MAIN 액션을 가진 이름과 LAUNCHER 분류를 가진 이름이 겹치지 않음 | 같음 (`get_main_activities`) |
| 이름 패턴 | 클리너·부스터·배터리·와이파이 키워드 (한·영) | bool | 앱 라벨(시스템 로케일 = 한국어)·패키지명 | 매니페스트 라벨 `ko` 리소스, 없으면 기본 라벨·패키지명 |

- 어휘 목록은 `ml/schema/feature_schema_v1.json` 하나로 관리하고, 빌드 시 앱 asset으로 복사 → 두 쪽이 같은 파일을 읽음 (생성: `ml/schema/build_schema_v1.py`, 2026-10-03)
- **이름 정규화**: 문자열을 `.`로 나눈 마지막 조각을 소문자로 → vocab에 있으면 1 (`android.intent.action.BOOT_COMPLETED` → `boot_completed`). MH-1M 추출 코드(Malware-Hunter/SF23-AMGenerator `extraction.py`)를 직접 읽어 확인한 규칙과 같음
- **변경(2026-11-24, T023)**: 권한·컴포넌트·SDK 판·런처 아이콘도 PackageManager가 아니라 매니페스트에서 읽는다. `requestedPermissions`에는 시스템이 더한 암묵 권한이 섞이고, `queryIntentActivities`는 실행 시 상태(꺼진 컴포넌트)를 따라 PC 특징과 어긋나기 때문. 기기에서 PackageManager로 받는 것은 `sourceDir`와 라벨(시스템 로케일)뿐
  - androguard 4.1.4(+apkInspector)의 처리를 그대로 따른다: ZIP은 압축 방식을 조작해도 저장으로 읽음, 바이너리 XML은 모르는 조각 건너뛰기·문자열 수 보정·네임스페이스 조각 위치, 시스템 속성은 리소스 ID로 이름을 고침(androguard 표와 같은 범위·빈 칸), 이름·값 정리(`_fix_name`·`_fix_value`: 박힌 접두어, 첫 NUL에서 자름), 값 종류별 문자열(`format_value`), 문자열은 Python 코덱과 같은 결과(UTF-16 BOM, 잘못된 바이트 대체)
  - 요소 찾기는 두 방식: 권한·컴포넌트·uses-sdk는 네임스페이스 없는 태그와 android 태그 둘 다(`find_tags`), intent-filter·런처는 네임스페이스 없는 태그만. `uses-permission-sdk-23`과 activity-alias의 intent-filter는 세지 않음. uses-sdk가 여럿이면 문서 순서로 처음 값(PC `extract.py`도 이렇게 고정 — androguard 기본은 집합 순서라 실행마다 다를 수 있었음)
  - 시험한 거절 모양(숫자가 아닌 min·targetSdk, 이름 없는 action, 로컬 헤더 가림 등)은 PC와 기기가 같이 거절한다. 그 밖에 PC만 거절하는 모양이 있다(lxml이 받지 않는 XML, resources.arsc가 필요한 값 등) — 이때 기기는 벡터를 낸다. 기기에서 거절은 "판정 불가"로 남기고, 그것을 의심 신호로 볼지는 분류기 연결(T033·T050) 때 정한다
  - uses-sdk가 여럿이면 값과 거절 판단 모두 문서 순서로 처음 값 (PC는 androguard가 APK를 열 때 하는 int() 검사를 받지 않고 `extract.py`에서 같은 규칙으로 다시 판단)
  - 남의 앱 파일을 읽으므로 앱이 죽지 않게 했다: 재귀 없음, 문자열은 쓸 때만 풀고 같은 값은 사본을 만들지 않음, 매니페스트는 8MB까지만 읽음, 요소를 한 번씩만 훑음(중첩에서 제곱 시간 없음), 실패는 `ManifestException`. 시험한 모양: 잘림·바이트 변조·깊이 2만 중첩·같은 큰 문자열 300번 참조·조각 크기 조작. 설치된 패키지 이름과 매니페스트 package가 다르면(ZIP 미끼) 판정하지 않음
  - 알려진 차이: action·category 이름이 리소스 참조(`@7F…`)면 PC는 resources.arsc로 풀고 기기는 그대로 둔다(aapt2는 이런 매니페스트를 만들지 않음). int 범위를 넘는 SDK 값
- **변경(2026-10-03)**: 인텐트는 원래 "액션마다 `queryBroadcastReceivers`"였으나, MH-1M 특징은 **activity·receiver·service의 매니페스트 intent-filter 전체**에서 나온 값이라 receiver 조회로는 의미가 어긋남 → 기기에서도 매니페스트를 직접 읽음. 같은 파싱으로 컴포넌트 수·런처 아이콘·min/targetSdk도 얻어 PC(androguard)와 일치시키기 쉬움
- **광고 SDK 규칙(T021, 2026-10-06)**: 정확한 클래스 이름이 아니라 **패키지 접두어**로 대조. LibChecker-Rules(60fe485)는 정확한 이름 위주라 SDK 버전이 바뀌면 놓침(예: Unity Ads 4.21 컴포넌트 `com.unity3d.ads.adplayer.*`가 규칙엔 없음). 23종의 출처: 최신 AAR 매니페스트 18종(컴포넌트 112개 전부 일치) + 카울리 예제 앱 매니페스트 1종 + LibChecker 규칙 4종(중국계). 바이트댄스 공용 라이브러리(`com.ss.android.*`, `embedapplog`)는 틱톡 등 정상 앱에도 있어 **오탐 방지를 위해 제외**. εxodus 추적기 DB(AGPL)는 대조만 하고 복사하지 않음
- 한계: 컴포넌트를 선언하지 않는 SDK는 못 잡음(카울리는 앱이 직접 선언해서 잡힘). TNK·애드팝콘·버즈빌·애드믹서는 컴포넌트 확인 못 해 제외 → 직접 수집 APK(T025)에서 보충
- MH-1M이 주는 특징은 **권한·인텐트 필터·APK 크기(VT_SIZE)**뿐. 광고 SDK·컴포넌트 수·SDK 버전·아이콘·이름 패턴은 직접 수집 APK(T025)에서만 학습됨 → 베이스라인(T040)은 MH-1M 3그룹으로 먼저
- v1은 첫 모델 학습(T040) 시점에 동결. 그 전 변경은 v1 안에서 함. 동결 뒤 스키마가 바뀌면 `featureSchemaVersion`을 올리고, 앱은 지원하지 않는 버전의 모델을 받지 않음 (openapi `ModelRelease`)

### 층 2 런타임 신호 (학습 안 함)

| 신호 | 얻는 법 | 근거 코드 |
|---|---|---|
| 설치 출처가 스토어 아님 | `getInstallSourceInfo` (API 30+) / `getInstallerPackageName` | `SIDELOADED` |
| 기기 관리자 활성 | `DevicePolicyManager.getActiveAdmins()` | `DEVICE_ADMIN` |
| 접근성 서비스 켜짐 | `AccessibilityManager.getEnabledAccessibilityServiceList` | `ACCESSIBILITY_SERVICE` |
| 알림 접근 켜짐 | `NotificationManagerCompat.getEnabledListenerPackages` | `NOTIFICATION_LISTENER` |
| 설치 후 실행 없이 백그라운드 실행 잦음 (UsageStats 허용 시) | `UsageStatsManager.queryEvents` | `FREQUENT_BACKGROUND_LAUNCH` |
| 유명 앱과 라벨 같은데 서명 다름 | 내장 서명 목록(카카오톡·네이버·은행 등) 대조 | `IMPERSONATION` |

- ⚠️ 다른 앱의 "다른 앱 위에 그리기" **허용 여부**는 일반 앱이 조회 불가 → 층 1의 **요청 여부**만 씀

## 3. 출력

```kotlin
data class Verdict(
    val level: RiskLevel,          // SAFE, CAUTION, RISK
    val score: Float,              // 0..1
    val modelVersion: String,      // "2026.11.1-int8"
    val featureSchemaVersion: Int, // 1
    val reasons: List<Reason>,     // 최대 3개, weight 내림차순
    val latencyMs: Int,            // 특징 추출 + 추론 (NFR-01 측정용)
)
data class Reason(val code: ReasonCode, val weight: Float, val evidence: Map<String, String>)
```

- 임계값: `score ≥ thresholds.risk` → RISK, `≥ thresholds.caution` → CAUTION. 값은 검증셋에서 **앱 단위 FPR ≤ 5%를 만족하는 최소 risk 임계값**으로 정함 (constitution I, spec §4 지표 정의)
- 근거 산출(v1): 이 앱에서 켜진 특징 그룹 중 **전역 SHAP 중요도 상위** + 층 2 규칙 적중 → 합쳐서 상위 3개. 앱별 정밀 기여도는 v2

## 4. 근거 코드 → 문구

| 코드 | 시니어 문구 (해요체·전문용어 없음) | 보호자 문구 (구체값 포함) |
|---|---|---|
| `AD_SDK_HEAVY` | 광고를 많이 띄우는 앱이에요 | 광고 SDK {n}개 포함 ({sdks}) |
| `OVERLAY_PERMISSION` | 다른 앱 위에 화면을 띄울 수 있어요 | "다른 앱 위에 표시"(`SYSTEM_ALERT_WINDOW`) 권한 요청 — FR-011 사전 경고 신호 |
| `LAUNCHER_ICON_HIDDEN` | 앱 화면에 아이콘이 안 보이게 숨어 있어요 | 런처 아이콘 없음 (숨김 설치) |
| `SIDELOADED` | 앱 마켓이 아닌 곳에서 설치됐어요 | 설치 출처: {source} |
| `DEVICE_ADMIN` | 쉽게 지울 수 없게 막아 두었어요 | 기기 관리자 권한 활성 |
| `ACCESSIBILITY_SERVICE` | 화면 내용을 읽을 수 있게 켜져 있어요 | 접근성 서비스 활성 |
| `NOTIFICATION_LISTENER` | 알림 내용을 읽을 수 있어요 | 알림 접근 권한 활성 |
| `SMS_CALL_ACCESS` | 문자나 통화 기록을 볼 수 있어요 | SMS·통화 기록 권한 요청 |
| `INSTALL_OTHER_APPS` | 다른 앱을 몰래 설치할 수 있어요 | `REQUEST_INSTALL_PACKAGES` 요청 |
| `BOOT_AUTOSTART` | 폰을 켜면 저절로 실행돼요 | `BOOT_COMPLETED` 수신 |
| `IMPERSONATION` | {유명앱}인 척하는 가짜 앱일 수 있어요 | 라벨 "{label}", 서명이 공식 앱과 다름 |
| `FREQUENT_BACKGROUND_LAUNCH` | 안 써도 혼자 자주 실행돼요 | 최근 7일 백그라운드 실행 {n}회 |
| `CLEANER_BOOSTER_PATTERN` | "청소·속도 향상" 앱처럼 보이는데 광고가 많아요 | 클리너·부스터 키워드 + 광고 SDK |

- 문구 원칙: [토스 8가지 라이팅 원칙](https://toss.tech/article/8-writing-principles-of-toss) — 겁주지 않기(Suggest than force), 쉬운 말
- 분류 기준 참고: Play Protect "Disruptive ads" 정의 — *앱 밖에서 광고 표시, 쉽게 닫을 수 없음, 기기 기능 방해* ([Play Protect 경고 문구](https://developers.google.com/android/play-protect/warning-strings))

## 5. 일치 테스트 (Parity test)

- 같은 APK 20개를 (1) 에뮬레이터에 설치해 기기 추출기로, (2) PC에서 androguard로 특징 추출 → **층 1 특징 벡터가 비트 단위로 같아야 통과**
  - 구현(T024): 기기 추출기는 매니페스트 해석이 순수 Kotlin이라 JVM 시험 `core:classifier` `ParityTest`로 대조한다. 라벨만 기기(PackageManager)가 주므로 기대값의 라벨을 입력으로 쓴다
  - CI: 우리가 쓴 매니페스트로 만든 작은 APK — aapt2로 만든 2개(`ml/extract/parity_fixture/a·b`, build-tools 36.0.0 고정)와 바이너리 XML·ZIP을 직접 쓴 32개(`craft.py`: 네임스페이스 태그, 박힌 접두어, NUL·BOM 값, 빈 이름, 여러 uses-sdk, 값 종류, 속성 ID 빈 칸, 조각 크기 조작, ZIP 압축 방식 조작·데이터 디스크립터·로컬 헤더 가림, 깊이 2만 중첩, 같은 큰 문자열 여러 번 참조 등). 기대값 `expected.jsonl`이 androguard 결과와 같은지는 `ml/extract/parity_expected.py`의 `_check`가 CI 파이썬 단계에서 확인
  - 로컬: 받은 APK 150개 (`ml/data/apk`, 레포 밖) — 2026-11-24 150/150 일치
  - 확인하지 못한 것: JVM 시험이라 ART(기기 런타임)에서 돌린 것이 아니다. 문자열 해석은 Python 코덱을 직접 옮겨 런타임 디코더에 기대지 않지만, `BigDecimal`·`ln1p`·파일 읽기는 ART에서 따로 확인해야 한다. 실기기 라벨(PackageManager)이 PC의 ko 라벨과 같은지도 기기에서 확인 필요
- 같은 벡터에 대해 PC 원본 모델 점수와 기기 INT8 모델 점수 차이 ≤ 0.02 (LiteRT 양자화 정확도 검증 절차)
- 어휘 파일: 앱 asset은 빌드 때 `ml/schema/feature_schema_v1.json`을 그대로 복사한다(`core:classifier` `copyFeatureSchema`). 따로 두지 않으므로 해시 대조가 필요 없음

## 6. 성능 예산 (NFR-01: p95 ≤ 200ms)

| 단계 | 예산 |
|---|---|
| PackageManager 조회 (권한·컴포넌트·서명) | 60ms |
| 매니페스트 파싱 (base.apk 열기 + 바이너리 XML) | 60ms 🔶 실측 필요 |
| SDK 규칙 대조 | 30ms |
| 추론 | 30ms |
| 여유 | 20ms |

- 측정: Jetpack Microbenchmark, 갤럭시 A35급 실기기 + Pixel_8_API_35 에뮬레이터
