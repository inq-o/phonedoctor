# 폰주치의 서버

시니어 기기와 보호자 기기 사이의 연결·승인 요청을 중계하는 API 서버. 결정과 구성은 [ADR-0001](../docs/adr/0001-backend.md), API 계약은 [openapi.yaml](../specs/001-mvp/contracts/openapi.yaml).

- Ktor 3 + Exposed + Flyway, Postgres (운영 Neon), Cloud Run
- 인증: Firebase ID 토큰(Admin SDK) + App Check 토큰(JWKS로 직접 검증)
- 앱 Gradle 빌드와 분리된 빌드다. 이 폴더에서 `./gradlew` 실행

## 구현 상태

| 묶음 | 경로 | 상태 |
|---|---|---|
| devices | `POST /devices`, `PATCH·DELETE /devices/{id}`, `PUT /devices/{id}/status` | 구현 |
| pairing | `/pairing-codes`, `/links` | 다음 |
| alerts | `/alerts` | 그다음 |
| feedback·models | `/feedback/false-positives`, `/models/latest` | 나중 |

## 시험

```bash
./gradlew test
```

- Postgres 실행 파일을 시험 안에서 띄운다(zonky embedded-postgres). Docker 필요 없음
- 응답 본문을 `openapi.yaml` 스키마와 대조한다(`Contract.check`, JSON Schema 2020-12, 날짜·URI 형식 포함)
- 토큰은 가짜 검증기(`FakeVerifier`)로 바꾼다. 실제 Firebase 검증은 배포 뒤 실기기로 확인

## 환경 변수

| 이름 | 값 |
|---|---|
| `PORT` | Cloud Run이 넣음 (기본 8080) |
| `DATABASE_URL` | `jdbc:postgresql://<Neon 호스트>/<DB>?sslmode=require` |
| `DATABASE_USER`, `DATABASE_PASSWORD` | Neon 역할. 비밀번호는 Secret Manager로 넣음 |
| `FIREBASE_PROJECT_ID` | `phonedoctor-a33d3` |
| `FIREBASE_PROJECT_NUMBER` | Firebase 콘솔 > 프로젝트 설정 > 일반의 프로젝트 번호 (App Check 토큰 발급자·대상 확인용) |
| `APP_CHECK_APP_IDS` | 받을 Firebase 앱 ID, 쉼표 구분 (`google-services.json`의 `mobilesdk_app_id`) |

- Firebase Admin SDK는 기본 인증 정보(ADC)를 쓴다. Cloud Run에서는 서비스 계정이 자동으로 쓰이고, 로컬 실행은 `gcloud auth application-default login` 뒤에 된다

## 배포 (처음 한 번은 사람이 할 일)

1. Neon 가입 → 프로젝트 생성(리전 가까운 곳) → 연결 정보 확인
2. Google Cloud 콘솔에서 Firebase 프로젝트(`phonedoctor-a33d3`)에 결제 계정 연결 + 예산 알림 (Cloud Run 무료 한도 안이어도 결제 계정 필요)
3. `gcloud run deploy phonedoctor-api --source server/ --region asia-northeast3 --allow-unauthenticated --set-env-vars ... --set-secrets DATABASE_PASSWORD=...`
   - `--allow-unauthenticated`: 인증은 Cloud Run이 아니라 앱 토큰(Firebase)으로 한다
