# 폰주치의 서버

시니어 기기와 보호자 기기 사이의 연결·승인 요청을 중계하는 API 서버. 결정과 구성은 [ADR-0001](../docs/adr/0001-backend.md), API 계약은 [openapi.yaml](../specs/001-mvp/contracts/openapi.yaml).

- Ktor 3 + Exposed + Flyway, Postgres (운영 Neon), Cloud Run
- 인증: Firebase ID 토큰(Admin SDK) + App Check 토큰(JWKS로 직접 검증)
- 푸시: FCM HTTP v1 (Admin SDK). DB 반영 뒤 응답 전에 보내고, 실패해도 요청은 성공 (`Push.kt`)
- 앱 Gradle 빌드와 분리된 빌드다. 이 폴더에서 `./gradlew` 실행

## 구현 상태

모든 API 경로는 `/v1` 아래에 있다 (예: `POST /v1/devices`). 상태 확인용 `GET /healthz`만 인증 없이 열려 있고, 예약 작업 `POST /internal/tick`은 앱 토큰 대신 Cloud Scheduler의 OIDC 토큰으로 인증한다.

| 묶음 | 경로 | 상태 |
|---|---|---|
| devices | `POST /devices`, `PATCH·DELETE /devices/{id}`, `PUT /devices/{id}/status` | 구현 |
| pairing | `POST /pairing-codes`, `POST /pairing-codes/{code}/accept`, `GET /links`, `POST /links/{id}/confirm`, `DELETE /links/{id}` | 구현 |
| alerts | `POST·GET /alerts`, `GET /alerts/{id}`, `POST /alerts/{id}/decision`, `POST /alerts/{id}/outcome` | 구현 |
| 예약 작업 | `POST /internal/tick` — 재알림 24시간·만료 7일·요청 삭제 90일·연락 끊김 48시간·기기 삭제 180일·지난 연결 코드 (`Jobs.kt`) | 구현 |
| feedback·models | `/feedback/false-positives`, `/models/latest` | 나중 |

## 시험

```bash
./gradlew test
```

- Postgres 실행 파일을 시험 안에서 띄운다(zonky embedded-postgres). Docker를 켤 필요 없음
- 응답 본문을 `openapi.yaml` 스키마와 대조한다(`Contract.check`, JSON Schema 2020-12, 날짜·URI 형식 포함)
- 토큰은 가짜 검증기(`FakeVerifier`), 푸시는 보낸 것을 모아 두는 가짜(`FakePusher`)로 바꾼다. 실제 Firebase 검증·FCM 전송은 배포 뒤 실기기로 확인
- 동시 요청 시험(`PairingRaceTest`, 요청 동시 생성·동시 응답 등)은 행 잠금을 빼면 실패하는 것을 확인해 둠

## 환경 변수

| 이름 | 값 |
|---|---|
| `PORT` | Cloud Run이 넣음 (기본 8080) |
| `DATABASE_URL` | `jdbc:postgresql://<Neon 호스트>/<DB>?sslmode=require` |
| `DATABASE_USER`, `DATABASE_PASSWORD` | Neon 역할. 비밀번호는 Secret Manager로 넣음 |
| `FIREBASE_PROJECT_ID` | `phonedoctor-a33d3` |
| `FIREBASE_PROJECT_NUMBER` | Firebase 콘솔 > 프로젝트 설정 > 일반의 프로젝트 번호 (App Check 토큰 발급자·대상 확인용) |
| `APP_CHECK_APP_IDS` | 받을 Firebase 앱 ID, 쉼표 구분 (`google-services.json`의 `mobilesdk_app_id`) |
| `SCHEDULER_AUDIENCE` | 예약 작업 토큰의 대상. `https://<Cloud Run 주소>/internal/tick` (첫 배포 뒤 주소가 나오면 넣음) |
| `SCHEDULER_SERVICE_ACCOUNT` | 예약 작업을 부르는 서비스 계정 이메일. 둘 중 하나라도 없으면 `/internal/tick`은 404 (꺼짐) |

- Firebase Admin SDK(ID 토큰 검증·FCM 전송)는 기본 인증 정보(ADC)를 쓴다. Cloud Run에서는 서비스 계정이 자동으로 쓰이고, 로컬 실행은 `gcloud auth application-default login` 뒤에 된다

## 배포 (처음 한 번은 사람이 할 일)

1. Neon 가입 → 프로젝트 생성(리전 가까운 곳) → 연결 정보 확인
2. Google Cloud 콘솔에서 Firebase 프로젝트(`phonedoctor-a33d3`)에 결제 계정 연결 + 예산 알림 (Cloud Run 무료 한도 안이어도 결제 계정 필요)
3. 명령은 모두 **저장소 루트**에서, 이 프로젝트를 기본값으로 두고 실행
   - `gcloud config set project phonedoctor-a33d3`
   - `gcloud services enable secretmanager.googleapis.com run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com fcm.googleapis.com`
4. Secret Manager에 DB 비밀번호 넣기
   - `printf '%s' '<Neon 비밀번호>' | gcloud secrets create phonedoctor-db-password --data-file=-`
   - Cloud Run 실행 서비스 계정(따로 안 정하면 Compute Engine 기본 서비스 계정 `<프로젝트 번호>-compute@developer.gserviceaccount.com`)에 그 비밀 읽기 권한:
     `gcloud secrets add-iam-policy-binding phonedoctor-db-password --member=serviceAccount:<서비스 계정> --role=roles/secretmanager.secretAccessor`
5. FCM 전송 권한: Cloud Run 실행 서비스 계정에 `roles/firebasecloudmessaging.admin`
   - `gcloud projects add-iam-policy-binding phonedoctor-a33d3 --member=serviceAccount:<서비스 계정> --role=roles/firebasecloudmessaging.admin`
6. 소스 배포용 빌드 권한: 빌드 서비스 계정(기본은 위와 같은 Compute Engine 기본 서비스 계정)에 `roles/run.builder`
   - `gcloud projects add-iam-policy-binding phonedoctor-a33d3 --member=serviceAccount:<서비스 계정> --role=roles/run.builder`
   - 근거: [소스 코드에서 배포](https://cloud.google.com/run/docs/deploying-source-code)의 필요 역할
7. 환경 변수 파일 `server/env.yaml` (커밋하지 않음. `APP_CHECK_APP_IDS`의 쉼표가 `--set-env-vars` 구분자와 겹쳐 파일로 넘긴다)
   ```yaml
   DATABASE_URL: "jdbc:postgresql://<Neon 호스트>/<DB>?sslmode=require"
   DATABASE_USER: "<Neon 역할>"
   FIREBASE_PROJECT_ID: "phonedoctor-a33d3"
   FIREBASE_PROJECT_NUMBER: "<프로젝트 번호>"
   APP_CHECK_APP_IDS: "<앱 ID 1>,<앱 ID 2>"
   ```
8. 배포
   - `gcloud run deploy phonedoctor-api --source server/ --region asia-northeast3 --allow-unauthenticated --env-vars-file server/env.yaml --set-secrets DATABASE_PASSWORD=phonedoctor-db-password:latest`
   - `--allow-unauthenticated`: 인증은 Cloud Run이 아니라 앱 토큰(Firebase)으로 한다
9. 예약 작업 (첫 배포로 서비스 주소가 나온 뒤)
   - `gcloud services enable cloudscheduler.googleapis.com`
   - 호출용 서비스 계정: `gcloud iam service-accounts create phonedoctor-scheduler`
   - `server/env.yaml`에 `SCHEDULER_AUDIENCE: "https://<서비스 주소>/internal/tick"`, `SCHEDULER_SERVICE_ACCOUNT: "phonedoctor-scheduler@phonedoctor-a33d3.iam.gserviceaccount.com"`을 넣고 8번 배포를 다시
   - 15분마다 호출: `gcloud scheduler jobs create http phonedoctor-tick --location asia-northeast3 --schedule "*/15 * * * *" --http-method POST --uri https://<서비스 주소>/internal/tick --oidc-service-account-email phonedoctor-scheduler@phonedoctor-a33d3.iam.gserviceaccount.com --oidc-token-audience https://<서비스 주소>/internal/tick`
   - 서비스가 인증 없이 열려 있어(`--allow-unauthenticated`) Cloud Run 호출 권한(`roles/run.invoker`)은 따로 필요 없고, 토큰은 서버가 확인한다(`GoogleOidcAuth`)

## 로컬 컨테이너 확인

```bash
docker build -t phonedoctor-api:local server/   # Docker Desktop을 켠 뒤
```

- 실행에는 Postgres와 Google 기본 인증 정보(ADC)가 필요하다. 없으면 마이그레이션 뒤 `Your default credentials were not found`로 멈춘다

## 정리 작업

- 요청·연결 코드·오래된 기기는 예약 작업(`Jobs.kt`)이 지운다. 아래는 그 밖의 것

- 보관 기간(24시간)이 지난 Idempotency-Key 기록은 서버가 새 POST를 처리한 뒤 인스턴스마다 1시간에 한 번 지운다. 요청이 없으면 쌓이는 기록도 없음. 정리가 실패해도 POST 응답에는 영향이 없고 다음 POST에서 다시 시도한다
