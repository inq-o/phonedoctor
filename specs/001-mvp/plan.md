# 구현 계획 (Plan): 폰주치의 MVP

- **날짜**: 2026-10-03 · **Spec**: [spec.md](spec.md)
- 형식: spec-kit `plan-template.md`

## 요약

- 시니어 기기에서 두 경로를 오프라인으로 수행 ([ADR-0003](../../docs/adr/0003-detection-approach.md))
  - **주**: 사용 기록(UsageStats) 주기 조회 → 조작 없는 포그라운드 전환을 앱별로 귀속·횟수 기록 (FR-009~012)
  - **보조**: 설치 변화 감지 → 특징 추출 → 2층 판정(LiteRT 모델 + 공개 규칙) (FR-001~008)
- 증상 기준 초과·"위험" 판정(보호자가 켰으면 "주의"도, FR-028)만 서버로 보내 보호자 승인. 허용 목록(FR-007)은 두 출처 모두에 적용 → 시니어가 시스템 삭제 확인창에서 삭제
- 앱은 현재 뼈대(Now in Android 구조)를 확장, 서버·학습 파이프라인은 같은 저장소의 별도 폴더

## 기술 맥락

| 항목 | 값 |
|---|---|
| 언어 | Kotlin 2.3 (앱·서버 Ktor), Python 3.11 (학습) |
| 주요 의존성 | Jetpack Compose·Material 3, Hilt, Room, DataStore, WorkManager, Navigation 3, LiteRT, Firebase(Auth·Messaging·App Check), Retrofit/OkHttp + kotlinx.serialization |
| 저장 | Room (로컬), Neon Postgres (서버, Cloud Run에서 접속 — ADR-0001) |
| 테스트 | JUnit4, Turbine, Compose UI Test, Roborazzi(스크린샷), Microbenchmark, openapi 계약 테스트 |
| 대상 | Android 8.0(API 26)~16+, targetSdk 36 |
| 성능 목표 | 판정 p95 ≤ 200ms, 감지 p95 ≤ 30분(실측 목표, 상한 보장 아님), 모델 ≤ 20MB |
| 제약 | 접근성 서비스·포그라운드 서비스·VPN 미사용, 위험 건만 전송 |

## 원칙 점검 (constitution)

| 원칙 | 점검 | 결과 |
|---|---|---|
| I 오탐 최소 | 분류기는 앱 단위 FPR ≤ 5%, 증상 감지는 이벤트 단위 오탐률 ≤ 5% + 기기·일당 오경고 측정 (spec §4 지표 정의), 허용 목록 | ✅ |
| II 승인 경계 | 삭제는 시스템 확인창, 강력 모드도 정지까지만 | ✅ |
| III 데이터 최소화 | `POST /alerts`에 앱 1개 정보만, 목록 API 없음 | ✅ |
| IV 온디바이스 | 판정 경로에 네트워크 없음 | ✅ |
| V 정책 | 접근성 서비스 미사용, 상시 알림 채널 | ✅ |
| VI 접근성 | ux-flows §5·§7 | ✅ (구현 시 테스트) |
| VII 측정 | `verdict`·`symptom_event` 테이블에 기록 | ✅ |
| VIII 구조 | NiA 모듈 관례 | ✅ — 예외는 아래 표 |

## 아키텍처

```mermaid
flowchart TB
    subgraph Device["시니어 기기"]
        WM[WorkManager<br/>최소 주기 15분 + 앱 실행 시] --> US[core:usage<br/>queryEvents ACTIVITY_RESUMED]
        US --> SY[전환 분류<br/>FR-012 휴리스틱]
        SY --> SE[(Room: symptom_event)]
        SY -->|기준 초과 & 허용목록 아님| OB
        WM --> CH[core:packages<br/>getChangedPackages]
        CH --> FX[core:classifier<br/>특징 추출]
        RULES[(LibChecker-Rules<br/>+ 광고 태그 DB)] --> FX
        FX --> M1[층1 LiteRT 모델]
        FX --> M2[층2 런타임 규칙]
        M1 & M2 --> V[Verdict]
        V --> DB[(Room: verdict, allow_entry)]
        V -->|RISK 또는 CAUTION+notifyCaution<br/>& 허용목록 아님| OB[(outbox)]
        OB --> NET[core:network]
        FCMR[FCM 수신] --> NOTI[core:notifications]
        NOTI --> UI[feature:senior-*]
        UI -->|이 앱 지우기| PI[PackageInstaller.uninstall]
    end
    subgraph Server["서버 (ADR-0001)"]
        API[Ktor REST API v1<br/>Cloud Run] --> STORE[(Neon: Device, Link, Alert)]
        API --> FCM[FCM HTTP v1]
        CRON[재알림 24h/48h] --> FCM
    end
    subgraph Guardian["보호자 기기"]
        GN[FCM 수신] --> GUI[feature:guardian-*]
        GUI --> GNET[core:network]
    end
    NET <--> API
    GNET <--> API
    FCM -.-> FCMR
    FCM -.-> GN
```

### 증상 감지 설계 (FR-009~012, 주 경로)

| 항목 | 내용 | 비고 |
|---|---|---|
| 조회 | 설치 감지와 같은 WorkManager 주기 작업 + 앱 실행 시 `UsageStatsManager.queryEvents(usage_cursor, now)` | 포그라운드 서비스 없음. 시스템 이벤트 보관 기간 안에서만 커서로 이어 읽음(무손실 보장 아님). 보관 기간보다 오래 조회하지 못한 구간은 감지 공백으로 기록하고 이어서 진행. 지원 최대 지연은 실증에서 확인한 보관 기간으로 정함 |
| 이벤트 | `ACTIVITY_RESUMED`(API 29+, 이전은 `MOVE_TO_FOREGROUND`) 중 폰주치의·런처·시스템 UI 제외 | 액티비티형 앱 밖 광고만 보임. 오버레이형은 FR-011 사전 경고로 보조 |
| 분류 | 사용자 조작 직후 전환(직접 실행·알림 탭·앱 간 전환)과 조작 없는 전환을 가르는 규칙 (FR-012) | 🔵 쓸 이벤트 조합·시간 창·예외는 **UsageStats 실증 실험**에서 정함. `ACTIVITY_RESUMED` 하나로는 구분되지 않음 |
| 기록·기준 | 광고로 센 전환만 `symptom_event`에 저장, 앱별 현지 날짜 하루 횟수가 기준을 넘으면 Alert(`source: symptom`). 같은 앱·버전에 열린 `symptom` 요청이 있으면(`alert_local`) 다시 보내지 않음 — 근거는 처음 기준을 넘은 날짜 것 그대로, 재알림은 FR-026 일정대로 (v1) | 기준값은 실증·T074에서 정함 🔶 |
| 실패 조건 | 권한 거부·프로세스 종료·화면 꺼짐·Doze에서 감지 가능성과 지연 | 실증 실험에서 측정 |

### 설치 감지 설계 (FR-002, 보조 경로)

| 방법 | 언제 | 비고 |
|---|---|---|
| `PackageManager.getChangedPackages(seq)` | WorkManager 주기 작업(15분), 앱 실행, 부팅 후 | API 26+. 순번은 재부팅 시 초기화 → `bootCount`와 같이 저장, 바뀌면 전체 재검사 |
| `ACTION_PACKAGE_ADDED`·`REPLACED` 동적 리시버 | 앱 프로세스가 살아 있을 때 | 매니페스트 등록은 Android 8부터 전달 안 됨 |
| 포그라운드 서비스 | **안 씀** | `specialUse` 선언·심사 부담, 상시 알림 강제, 배터리 |

- 결과: 감지 지연 p95 ≤ 30분 목표 (NFR-02, 실측). 15분은 최소 반복 간격이라 상한 보장이 아님. 즉시 감지가 필요하면 강력 모드(Device Owner)의 설치 출처 차단으로 대체

### 삭제 유도 (FR-030)

- `REQUEST_DELETE_PACKAGES` 권한 + `PackageInstaller.uninstall(VersionedPackage, IntentSender)` → 시스템 확인창
- 결과는 `IntentSender` 콜백의 `EXTRA_STATUS`로 받아 `outcome` 보고
- 반드시 앱이 화면 앞에 있을 때 호출 (백그라운드 액티비티 실행 제한)

## 저장소 구조

```text
phonedoctor/
├── .specify/memory/constitution.md
├── specs/001-mvp/            # 이 문서들
├── docs/adr/                 # 결정 기록
├── app/                      # 한 앱 + 역할 선택 (ADR-0002 A)
├── build-logic/              # 컨벤션 플러그인 (현재 8종)
├── core/
│   ├── model/                # NEW  Verdict, RiskLevel, ReasonCode, AppInfo (순수 Kotlin)
│   ├── common/               # NEW  디스패처, Result
│   ├── data/                 # 있음  저장소 계층
│   ├── data-test/            # 있음
│   ├── database/             # 있음  Room 엔티티를 data-model.md §3·4로 교체
│   ├── datastore/            # NEW  설정 (data-model.md §5)
│   ├── network/              # NEW  openapi 클라이언트, 인증 인터셉터, outbox 전송
│   ├── notifications/        # NEW  채널 4종, FCM 서비스
│   ├── packages/             # NEW  설치 앱 읽기·변화 감지 (PackageManager 래퍼)  ※NiA에 없음
│   ├── usage/                # NEW  사용 기록 조회·전환 분류 (FR-009~012)       ※NiA에 없음
│   ├── classifier/           # NEW  특징 추출 + LiteRT 실행 + 층2 규칙 (보조)  ※NiA에 없음
│   ├── designsystem/         # 있음  시니어·보호자 테마 2종
│   ├── ui/                   # NEW  공용 컴포저블 (앱 카드, 근거 목록)
│   └── testing/              # 있음
├── feature/
│   ├── appscan/              # 있음  → senior-home으로 이름 변경
│   ├── onboarding/{api,impl}
│   ├── senior-home/{api,impl}
│   ├── senior-appdetail/{api,impl}
│   ├── pairing/{api,impl}
│   ├── guardian-home/{api,impl}
│   ├── guardian-alert/{api,impl}
│   └── settings/{api,impl}
├── sync/work/                # NEW  WorkManager 작업 (NiA sync:work 관례)
├── ml/                       # NEW  Python 학습 파이프라인 (Gradle 밖)
│   ├── schema/feature_schema_v1.json
│   ├── extract/  train/  eval/  export/
│   └── rules/runtime_bonus.yaml
└── server/                   # NEW  Ktor (ADR-0001)
```

- 모듈 이름은 NiA 관례(`core:*`, `feature:*/{api,impl}`, `sync:work`)를 따름
- 테스트용 광고 앱 APK 생성기: `testapps/`(🔶 P1) — 광고 SDK·숨김 아이콘·오버레이 권한을 조합한 가짜 앱(분류기 시연·일치 테스트)과, 배경에서 전면 액티비티를 띄우는 재현 앱(액티비티형 앱 밖 광고, 증상 감지 시연·T074 recall)

## 학습 파이프라인 (`ml/`)

| 단계 | 도구 | 산출물 |
|---|---|---|
| 데이터 | MH-1M(Figshare npz) 권한·인텐트 부분, CIC-AAGM2017, KronoDroid, 직접 수집 APK | `data/` (git 제외) |
| 추출 | androguard 4.1.4 고정 / apk-info (변조 APK) | 특징 parquet (스키마 v1) |
| 학습 | scikit-learn·LightGBM 베이스라인 → (Q4) MLP | 모델 + 학습 로그 |
| 임계값 | 검증셋에서 앱 단위 FPR ≤ 5%를 만족하는 최소 risk 임계값 (classifier.md §3) | `thresholds.json` |
| 변환 | LiteRT 변환 + ai-edge-quantizer INT8 | `.tflite` ≤ 20MB |
| 평가 | 별도 테스트셋 + 대조군(시티즌코난·Play Protect 수동 실행) | 비교표 (앱 단위 FPR). 증상 감지 평가는 T074 |

- ⚠️ MH-1M에는 컴포넌트 정보가 없어 **광고 SDK 특징은 직접 수집 APK에서만** 학습됨 → 수집 목표: 광고·회색지대 앱 300개 이상 + 시니어가 실제로 쓰는 정상 앱 300개 이상 🔶
- 실험마다 `ml/runs/{날짜}_{이름}/`에 파라미터·환경·지표 저장 (constitution VII)

## 테스트 전략

| 층 | 대상 | 방법 |
|---|---|---|
| 단위 | 특징 추출, 층2 규칙, 임계값, 조사 처리 | JUnit, 가짜 PackageManager |
| 단위 | 전환 분류 휴리스틱 (FR-012) | 기록한 이벤트 열을 고정 입력으로 JUnit |
| 평가 | 증상 감지 recall·이벤트 단위 오탐률·기기·일당 오경고 | 재현 앱 + T026 정상 앱 에뮬레이터 실행 (T074) |
| 일치 | 기기 추출기 vs androguard | contracts/classifier.md §5 |
| 계약 | 서버 응답이 openapi와 일치 | 서버 테스트에서 스키마 검증 |
| UI | 시니어 화면 글꼴 200% | Compose UI Test, Roborazzi 스크린샷 |
| 성능 | 판정 지연 | Microbenchmark (실기기) |
| 종단 | F1′(증상)·F1(설치 시점) 전체 완주. 증상 감지 Recall(G2)은 평가 행(T074)에서 필수로 잼 | 에뮬레이터 2대 + 재현 앱·테스트 광고 앱 (quickstart.md) |

## 예외 기록 (Complexity Tracking)

| 원칙과 다른 점 | 필요한 이유 | 더 단순한 안을 안 쓴 이유 |
|---|---|---|
| NiA에 없는 `core:packages`, `core:usage`, `core:classifier` | 이 앱의 핵심 도메인 | `core:data`에 넣으면 PackageManager·UsageStatsManager·LiteRT 의존이 저장소 계층에 섞임 |
| 저장소 안에 `ml/`, `server/` | 특징 스키마 파일을 앱과 학습이 공유 | 저장소를 나누면 스키마 동기화가 수동이 됨 |
