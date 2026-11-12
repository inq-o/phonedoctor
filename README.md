# 폰주치의

시니어 스마트폰에 설치된 악성·광고 앱을 기기 안에서 판별하고, 가족(보호자)의 승인을 거쳐 삭제·차단하는 Android 앱.

- 기획 문서: [specs](specs/README.md) · 원칙: [.specify/memory/constitution.md](.specify/memory/constitution.md) · 결정 기록: [docs/adr](docs/adr)
- 최우선 지표: 오탐률(FPR) 5% 이하

## 구조

| 경로 | 내용 |
|---|---|
| `app/` | 앱 진입점·내비게이션 |
| `feature/` | onboarding(첫 설정) · senior-home(홈·검사 기록) · senior-appdetail(앱 상세·삭제), 각 `api`/`impl` |
| `core/` | usage(사용 기록 판정기) · packages(설치 앱·삭제) · data · database · datastore · notifications · model · designsystem · testing |
| `sync/work/` | 주기 검사 (WorkManager) |
| `testapps/` | 시험용 광고 재현 앱·사용 기록 수집기와 기기 시험 스크립트 (`poc/`) |
| `build-logic/` | Gradle 컨벤션 플러그인 ([Now in Android](https://github.com/android/nowinandroid) 구조) |
| `ml/` | 학습 데이터 전처리·특징 스키마 (데이터 파일은 저장소에 포함하지 않음) |

## 빌드

```sh
./gradlew test :app:assembleDebug
```

JDK 17 이상, Android SDK 36.

## 기기에서 시험

지금 앱은 시니어 기기 쪽 로컬 경로(첫 설정 → 앱 밖 광고 증상 감지 → 확인이 필요한 앱 → 시스템 삭제)까지 있다. 가족 연결·서버는 아직 없다.

```sh
./gradlew :app:assembleDebug :testapps:bgad:assembleDebug
ANDROID_SERIAL=<기기> testapps/poc/product_smoke.sh
```

재현 앱이 광고 화면을 5번 띄우고, 앱이 그 앱을 짚어 확인 요청을 여는지 본다. 재현 앱을 깔기 때문에 시험용 기기에서만 돌린다. 감지 경로가 도는지 보는 스모크이고 탐지율·오탐률 측정은 아니다.

## 라이선스

[Apache License 2.0](LICENSE)
