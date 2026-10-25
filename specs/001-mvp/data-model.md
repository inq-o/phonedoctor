# 데이터 모델

- 로컬(Room, 시니어·보호자 기기)과 서버(Neon Postgres, ADR-0001)로 나눔
- **로컬에만 있는 것**: 설치 앱 스냅샷, 설치 시점 판정 기록, 증상 감지 원본 이벤트(`symptom_event`) → 서버로 안 감 (constitution III)
- 서버로 가는 증상 정보는 기준을 넘은 앱 1개의 집계값(`SymptomEvidence`: date, count, firstAt, lastAt, detectorVersion)뿐
- API 표현은 [contracts/openapi.yaml](contracts/openapi.yaml)

## 1. 관계도

```mermaid
erDiagram
    DEVICE ||--o{ LINK : "protected 또는 guardian"
    LINK }o--|| DEVICE : ""
    DEVICE ||--o{ ALERT : "protected 기기가 생성"
    ALERT ||--o| DECISION : "보호자 1명이 결정"
    ALERT ||--o| OUTCOME : "시니어 기기가 보고"
    PAIRING_CODE }o--|| DEVICE : "protected 기기가 발급"

    DEVICE {
      string deviceId PK
      string firebaseUid
      enum role "protected|guardian"
      string displayName
      string fcmToken
      bool notifyCaution
      timestamp createdAt
      timestamp lastSeenAt "서버가 마지막으로 요청을 받은 시각 (참고)"
      timestamp lastCheckedAt "상태 보고의 마지막 검사 (F6 기준)"
      json protection "ProtectionState (시니어)"
    }
    LINK {
      string linkId PK
      string protectedDeviceId FK
      string guardianDeviceId FK
      enum status "pending|active|revoked"
      timestamp createdAt
      timestamp activatedAt
      timestamp revokedAt
    }
    PAIRING_CODE {
      string code PK "6자리, 10분"
      string protectedDeviceId FK
      timestamp expiresAt
      bool used
    }
    ALERT {
      string alertId PK
      string protectedDeviceId FK
      enum status "open|decided|resolved|expired"
      enum source "install_scan|symptom"
      enum kind "approval|notice"
      json app "AppInfo"
      json verdict "Verdict (install_scan 필수)"
      json symptom "SymptomEvidence (symptom 필수, 열린 동안 최신 값으로 갱신)"
      timestamp detectedAt
      timestamp remindAt
      timestamp expiresAt "생성+90일 삭제"
    }
    DECISION {
      string alertId PK, FK
      string guardianDeviceId FK
      enum decision "recommend_uninstall|allow"
      string messageToSenior
      timestamp decidedAt
    }
    OUTCOME {
      string alertId PK, FK
      enum outcome "uninstalled|declined|already_removed|suspended|allowed"
      timestamp occurredAt
    }
```

## 2. 서버 개체 규칙

| 개체 | 불변 조건 | 삭제 |
|---|---|---|
| Device | `firebaseUid`당 1개. 역할은 바꿀 수 없음 (바꾸려면 앱 데이터 초기화) | 기기 삭제 API 또는 마지막 접속 180일 후 |
| Link | (protected, guardian) 쌍당 active 1개. 보호자는 최대 3명 (무료 1명, 🔶 구독 시 3명) | revoked 즉시 해당 링크로 만든 요청 데이터 삭제 |
| PairingCode | 기기당 유효 코드 1개. 수락되면 `used` | 만료 1시간 후 |
| Alert | (protectedDevice, packageName, signingCertSha256, versionCode)당 open 1개 | 생성 90일 후 (NFR-09) |
| Decision | Alert당 1개 — **먼저 쓴 쪽만 성공** (트랜잭션 조건: `status == open`) | Alert와 같이 |

### Alert 상태 전이

```mermaid
stateDiagram-v2
    [*] --> open: createAlert (시니어 기기)
    open --> decided: decideAlert (보호자, 첫 응답)
    open --> resolved: reportOutcome already_removed (시니어가 먼저 지움)
    open --> expired: 7일 무응답
    decided --> resolved: reportOutcome (uninstalled / declined / allowed / suspended)
    resolved --> [*]
    expired --> [*]
```

- `declined`(시니어가 시스템 창에서 취소)도 resolved로 닫고, 시니어 기기 로컬에서 하루 뒤 재안내 (서버 상태는 그대로)

## 3. 로컬 DB (Room) — 시니어 기기

| 테이블 | 주요 열 | 용도 |
|---|---|---|
| `installed_app` | packageName PK, label, versionCode, signingCertSha256, installSource, firstInstallTime, lastUpdateTime, isSystem, lastScannedAt | 현재 설치 앱 스냅샷 |
| `verdict` | id PK, packageName, versionCode, level, score, modelVersion, featureSchemaVersion, reasonsJson, latencyMs, createdAt | **모든 설치 시점 판정 기록** (constitution VII — 실험 로그 겸용) |
| `symptom_event` | id PK, packageName, signingCertSha256, versionCode, className, resumedAt, rule, detectorVersion. **고유 (packageName, className, resumedAt)** | 광고로 센 포그라운드 전환 (FR-009·010). 서명·버전은 분류할 때의 `installed_app` 값으로 채움(조회 시점에 이미 지워진 앱이면 버림). 하루 횟수·허용 목록·Alert 식별은 (packageName, signingCertSha256, versionCode)로 묶음. 정상으로 본 전환은 저장하지 않음. 30일 보관 |
| `usage_cursor` | id=1, lastEventTime | `queryEvents` 다음 조회 시작 시각. 시작 시각이 포함되므로 `lastEventTime`부터 다시 읽고(+1 안 함), 겹쳐 읽은 이벤트는 `symptom_event` 고유 제약으로 무시 (같은 시각 이벤트의 중복·누락 둘 다 막음) |
| `allow_entry` | (packageName, signingCertSha256, versionCode) PK, allowedBy, createdAt | 보호자가 허용한 앱. 같은 버전은 다시 안 물음 |
| `alert_local` | alertId PK, packageName, signingCertSha256, versionCode, source, kind, status, decision, guardianName, messageToSenior, outcome, nextNudgeAt | 서버 요청의 로컬 사본 + 재안내 일정. 허용 앱의 notice를 같은 조합에 1번만 보내는 판단도 여기서 |
| `outbox` | id PK, method, path, bodyJson, idempotencyKey, attempts, nextAttemptAt | 오프라인 중 쌓인 서버 호출 (WorkManager가 재전송) |
| `package_change_cursor` | id=1, sequenceNumber, bootCount | `getChangedPackages` 순번 |

## 4. 로컬 DB — 보호자 기기

| 테이블 | 주요 열 | 용도 |
|---|---|---|
| `link_cache` | linkId PK, protectedName, status, lastSeenAt, lastCheckedAt, protectionJson | 홈 화면, 연락 끊김 경고 (F6) |
| `alert_cache` | alertId PK, linkId, source, kind, appLabel, iconPng, level, reasonsJson, symptomJson, status, decisionJson, outcomeJson, updatedAt | 요청 목록·상세 (오프라인 열람) |

## 5. 설정 (DataStore)

| 키 | 기기 | 기본값 |
|---|---|---|
| `role` | 공통 | 온보딩에서 선택 |
| `onboardingDone` | 공통 | false |
| `disclosureAcceptedVersion` | 시니어 | 0 (고지 문구가 바뀌면 다시 받음) |
| `usageAccessGranted` | 시니어 | false (증상 감지 주 경로, 꺼지면 설치 시점 판정만) |
| `lastStatusReportAt` | 시니어 | 없음 (`PUT /devices/{id}/status` 6시간 간격) |
| `falsePositiveFeedback` | 공통 | true (US7, 끌 수 있음) |
| `strongModeEnabled` | 시니어 | false (Device Owner일 때만 노출) |
| `seniorTextScale` | 시니어 | 1.0 (시스템 글꼴 배율에 곱하는 추가 배율, 1.0~1.3) |
