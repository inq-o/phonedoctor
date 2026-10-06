# 폰주치의

시니어 스마트폰에 설치된 악성·광고 앱을 기기 안에서 판별하고, 가족(보호자)의 승인을 거쳐 삭제·차단하는 Android 앱.

- 기획 문서: [specs](specs/README.md) · 원칙: [.specify/memory/constitution.md](.specify/memory/constitution.md) · 결정 기록: [docs/adr](docs/adr)
- 최우선 지표: 오탐률(FPR) 5% 이하

## 구조

| 경로 | 내용 |
|---|---|
| `app/` | 앱 진입점·내비게이션 |
| `feature/appscan/` | 설치 앱 점검 화면 (`api`/`impl`) |
| `core/` | data · database · designsystem · testing |
| `build-logic/` | Gradle 컨벤션 플러그인 ([Now in Android](https://github.com/android/nowinandroid) 구조) |
| `ml/` | 학습 데이터 전처리·특징 스키마 (데이터 파일은 저장소에 포함하지 않음) |

## 빌드

```sh
./gradlew test :app:assembleDebug
```

JDK 17 이상, Android SDK 36.

## 라이선스

[Apache License 2.0](LICENSE)
