# ADR-0001: 서버 구현 방식

- **상태**: Accepted — **안 B (Ktor 자체 서버)**, 2026-10-03
- **날짜**: 2026-10-03
- **결정자**: 최인규
- 형식: MADR 계열 ADR 템플릿

## 맥락

- 서버가 하는 일: 기기 등록, 연결 코드, 승인 요청 중계(FCM), 모델 메타데이터 — [openapi.yaml](../../specs/001-mvp/contracts/openapi.yaml)
- 판정은 기기에서 끝나므로 서버 부하는 작음 (요청은 "위험" 판정 건만)
- 인증은 어느 안이든 **Firebase Auth**(시니어 익명 / 보호자 Google) + **App Check**(Play Integrity), 푸시는 **FCM** — 셋 다 무료 등급으로 충분
- 1인 개발, 11주차 원스토어 등록

## 선택지

### 안 A: Firebase 서버리스 (Cloud Functions 2세대 + Firestore)

| 항목 | 평가 |
|---|---|
| 복잡도 | 낮음 — 서버 관리 없음, 에뮬레이터 스위트로 로컬 테스트 |
| 비용 | 무료 등급(Spark)은 Functions 불가 → **Blaze(종량제) 필요**, 학기 규모 사용량은 무료 한도 안 |
| API 계약 | HTTPS 함수로 openapi 경로를 그대로 구현 (Express 라우터) |
| "먼저 쓴 응답만 유효" | Firestore 트랜잭션 |
| 재알림(24h) | Cloud Tasks 또는 예약 함수 |
| 실무 사례 | 소규모 앱 백엔드로 가장 흔함 |

- 장점: 인증·푸시·DB가 한 콘솔, 배포 명령 하나
- 단점: Firestore 쿼리 제약, 벤더 종속, Blaze는 카드 등록 필요(예산 알림 설정 가능)

### 안 B: 자체 서버 (Ktor 또는 Spring Boot on Cloud Run + Cloud SQL/Postgres)

| 항목 | 평가 |
|---|---|
| 복잡도 | 중간 — 컨테이너, DB 마이그레이션, 비밀 관리 |
| 비용 | Cloud Run 무료 한도 있음, **Cloud SQL은 상시 과금**(최소 월 1만 원대 🔶) — Neon 등 무료 Postgres로 대체 가능 |
| API 계약 | openapi에서 서버 스텁 생성(openapi-generator) |
| 언어 | Ktor면 앱과 같은 Kotlin, 모델 클래스 공유 가능 |

- 장점: 이력서·면접에서 "API 서버 설계·구현"을 직접 보여 줌, 벤더 종속 적음
- 단점: 운영할 것이 늘어남

### 안 C: Firestore 직접 접근 (API 서버 없음, 보안 규칙 + 함수 트리거만)

- 앱이 Firestore 문서를 직접 읽고 쓰고, 푸시는 문서 생성 트리거 함수가 보냄
- 장점: 코드 가장 적음, 실시간 구독
- 단점: **"API 명세"가 보안 규칙 + 문서 구조로 바뀜** → openapi 계약 무의미, 발표에서 API 설계를 보여 주기 어려움

## 트레이드오프

| | A | B | C |
|---|---|---|---|
| 구현량 | 중 | 상 | 하 |
| 운영 부담 | 하 | 중 | 하 |
| API 설계 보여주기 | ○ | ◎ | △ |
| 비용 위험 | 하 (Blaze 카드 등록) | 중 (DB) | 하 |
| 오프라인 재전송·멱등성 | 직접 구현 | 직접 구현 | SDK가 처리 |

## 결정

**안 B: Ktor on Cloud Run + Postgres(Neon 무료 등급)**. 인증·푸시·기기 검증은 그대로 Firebase Auth·FCM·App Check.

- 구성: `server/` (Gradle 별도 빌드) — Ktor + Exposed + Flyway, `openapi.yaml`에서 openapi-generator로 서버 인터페이스·앱 클라이언트 생성, firebase-admin-java로 ID 토큰 검증·FCM 발송
- 재알림(F3 24시간)·연락 끊김(F6 48시간): Cloud Scheduler → 서버 내부 엔드포인트 호출 (Cloud Tasks는 필요해지면)
- "먼저 쓴 응답만 유효": Postgres 조건부 UPDATE (`WHERE status = 'pending'`)
- 비용: Cloud Run·Scheduler 무료 한도, Neon 무료 등급. **GCP 결제 계정(카드) 등록은 필요** → 예산 알림 설정
- 참고 레포: `강의자료/TOPICS/자료/디자인_세팅_백엔드_레포_조사_2026-10-03.md` 3-2절

## 결과 (어느 안이든 공통)

- openapi.yaml은 A·B에서 그대로 유효. C를 고르면 contracts/에 Firestore 문서 구조·보안 규칙 문서로 교체
- 서버 코드는 저장소 루트의 `server/`에 둠 (Gradle 빌드와 분리)

## 할 일

1. [x] 안 선택 — B
2. [ ] Firebase 프로젝트 생성, Android 앱 2개 등록(ADR-0002 결과에 따라 1~2개)
3. [ ] App Check Play Integrity 연결 (Play Console 앱 등록 후 가능)
