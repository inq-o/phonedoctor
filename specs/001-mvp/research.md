# 조사 근거 (Research)

- 작성 2026-10-03. 9/29 기초 세팅 조사에 기획용 조사를 더한 것
- 각 결정이 어디서 왔는지 추적하는 용도

## 1. 경쟁·유사 서비스에서 가져온 것

| 서비스 | 확인한 동작 | 가져온 것 | 다르게 한 것 |
|---|---|---|---|
| Google Family Link | 자녀가 앱 설치 요청 → 부모 폰 알림 → 상세 → [승인]/[거부]. "지금 요청"은 자녀 기기에서 부모 비밀번호 입력으로 승인. 재설치·업데이트는 재승인 안 함 [1] | 알림 → 상세 → 2버튼 응답 구조, **같은 앱 재설치 시 재승인 생략**(허용 목록) | 아동 계정 전제 X, 설치 **전** 승인이 아니라 설치 **후** 판정 (일반 앱은 설치를 가로챌 수 없음) |
| Bark | 전체 기록을 보여 주지 않고 **AI가 걸러낸 건만** 보호자에게 알림 + 권장 행동 [2] | 위험 판정 건만 전송(원칙 III), 알림에 근거·권장 행동 포함 | 대상이 메시지가 아니라 앱 |
| 시티즌코난 | [악성앱 검사] 버튼 → 전화사기·원격제어·출처 불명 3분류 → 항목별 [삭제] [3] | 결과 분류를 사람 말로, 항목마다 삭제 버튼 | **사용자가 직접 실행할 필요 없음**, 광고 도배 앱 포함 |
| Google Play Protect | 위험 앱 알림 → 탭 → [제거]. 위협 21종 문구, 그중 **Disruptive ads**: "앱 밖에서 광고, 쉽게 닫을 수 없음, 기기 기능 방해" [4] | 알림 → 탭 → 삭제 흐름, 광고 앱 분류 기준 문장 | 보호자 승인 단계, 판정 근거 공개 |
| 삼성 Auto Blocker · One UI 9 | 사이드로딩 차단, 경찰청 목록 피싱 앱 설치·실행 차단 (계획서 [7][8]) | 설치 출처를 런타임 신호로 사용 | 스토어 통과 회색지대 앱이 대상 |
| Life360 | 가족 전원이 같은 앱, 초대 코드로 연결 | 6자리 코드 + QR 연결 (ADR-0002 안 A) | 연결 후 시니어 확인 단계 추가 |
| Android 고급 보호 모드 (AAPM) | Android 16, 사이드로딩 차단 등. `AdvancedProtectionManager.isAdvancedProtectionEnabled()`로 앱이 상태 확인 가능 [5] | 홈에 "고급 보호 켜짐" 표시, 켜져 있으면 `SIDELOADED` 신호 비중 낮춤 | — |

## 2. 기술 결정 근거

| 결정 | 근거 |
|---|---|
| 포그라운드 서비스 미사용, WorkManager + `getChangedPackages` | 매니페스트 `PACKAGE_ADDED`는 Android 8부터 미전달(기존 조사), FGS `specialUse`는 Play 선언 필요 |
| 삭제는 알림 탭 후 | 백그라운드 액티비티 실행 제한 |
| FCM data 메시지 + 앱이 알림 표시, high는 사람이 볼 것만 | Firebase 메시지 우선순위 문서, high인데 알림 안 띄우면 우선순위 강등 [6] |
| `USE_FULL_SCREEN_INTENT` 미사용 | Android 14부터 통화·알람 앱 한정 |
| minSdk 26 | `getChangedPackages`(API 26), ML Kit GenAI Prompt API 최소 API 26 [7] |
| 판정 2층 구조 | 공개 데이터셋엔 기기 상태(기기 관리자 활성 등)가 없음 → 학습 안 하는 규칙층으로 분리 |
| 다른 앱의 오버레이 **허용 여부** 미사용 | 일반 앱은 다른 앱의 AppOps 상태 조회 불가 → 요청 여부만 |
| AppFunctions (P2) | Android 16+ 시스템 AI 에이전트가 앱 기능을 호출하는 공식 API [8]. "에이전틱 AI" 트랙 연결점: 시니어가 음성으로 "폰 검사해 줘" |
| Gemini Nano 문장 다듬기 (P2) | ML Kit Prompt API, 지원 기기 한정 → 템플릿 문장이 기본값 [7] |

## 3. 디자인 기준

| 기준 | 핵심 | 출처 |
|---|---|---|
| 서울시 고령층 친화 디지털 접근성 표준 (모바일웹·앱) 10대 지침 | ① 글자 크고 선명 ② 필수 요소만 ③ 단순·친숙한 구조 ④ 쉬운 용어 ⑤ 상태 가시성 ⑥ 행동을 유발하는 컨트롤 ⑦ 빠르고 정확한 조작 ⑧ 피드백 ⑨ 오류 예방·복구 ⑩ 심리적 부담 감소 | [9][10] — 지침별 수치는 원문 PDF 확인 필요 🔵 |
| Material 3 / Android | 터치 48dp, Android 14부터 글꼴 200% 비선형 배율 → sp 단위면 자동 적용, 200%에서 UI 테스트 권장 | [11][12] |
| ISO 25556:2025 기반 고령자 디자인 체크리스트 | 본문 18~20px 권장, 터치 48px 권장, 시간 제한 금지, 고령자를 "결핍"으로 보지 않기 | humanity4ai/project_human (48c7258) |
| ui-ux-pro-max `--design-system` | "Trust & Authority" 패턴, 차분한 파랑 #0369A1 + 안심 초록, 작은 글씨·복잡한 내비·보라 그라데이션 금지 | nextlevelbuilder/ui-ux-pro-max-skill (09170ee), 오프라인 실행 |
| 토스 8가지 라이팅 원칙 | 예측 가능한 힌트, 군더더기 제거, 쉬운 말, **강요·공포 유발 금지**, 감정 공감, 해요체 통일 | [13] |
| UX 라이팅 관행 | 오류 = 무엇이 + 왜 + 어떻게, 빈 상태 = 무엇 + 왜 + 시작법, 확인창 버튼은 행동 이름 | 일반 UX 라이팅 가이드 |

## 4. 참고 저장소

| 저장소 | 쓰는 곳 | 라이선스 |
|---|---|---|
| android/nowinandroid | 모듈 구조·컨벤션 플러그인·sync:work | Apache |
| googlesamples/android-testdpc | 강력 모드 (QR 프로비저닝, suspend) | Apache |
| LibChecker/LibChecker-Rules | 광고 SDK 판별 규칙 DB | Apache |
| google-ai-edge/litert-samples | 추론 계층, INT8 양자화 검증 절차 | Apache |
| UriahShaulMandel/BaldPhone | 고령자 런처 UI (큰 버튼·SOS) 참고 | Apache |
| androguard 4.1.4 / delvinru/apk-info | 학습용 특징 추출 | Apache |

- GPL 저장소(AppManager·OwnDroid·Hail 등)는 읽기만 — 기존 조사 §1

## 5. 참고한 문서 형식

| 저장소 | ★ | 읽은 커밋 | 가져온 것 |
|---|---:|---|---|
| github/spec-kit | 139.8k | 8dfb15d (v1.1.0) | 문서 구조(constitution → spec → plan → contracts → tasks) |
| deanpeters/Product-Manager-Skills | 7.1k | 1b5a524 | 🔶가정/🔵미결 태그, proto-persona 형식. **라이선스 CC BY-NC-SA 4.0**(비상업) → 형식만 참고 |
| humanity4ai/project_human | 5 | 48c7258 | 고령자 디자인 체크리스트(ISO 25556:2025 기반) |
| nextlevelbuilder/ui-ux-pro-max-skill | 132.5k | 09170ee | 색·패턴 검색 결과. 시니어·Compose 질의는 결과 0건(웹 랜딩 중심) |
| android/skills | 7.6k | 42dc227 | appfunctions·ml-kit-genai-prompt-api 요건, 출시 전 Play 정책 점검 절차 |

## 출처

1. Google For Families Help, 「Purchase approvals on Google Play」, https://support.google.com/families/answer/7039872
2. All About Cookies, 「Bark Review 2026」, https://allaboutcookies.org/bark-review
3. 백세시대, 「'악성앱' 찾아내 삭제하는 피싱 범죄 예방 어플 '시티즌 코난'」, https://www.100ssd.co.kr/news/articleView.html?idxno=101509
4. Google, 「Play Protect warning strings」, https://developers.google.com/android/play-protect/warning-strings
5. Android Developers, 「Advanced Protection Mode」, https://developer.android.com/privacy-and-security/advanced-protection-mode
6. Firebase, 「Set and manage Android message priority」, https://firebase.google.com/docs/cloud-messaging/android/message-priority
7. android/skills `device-ai/ml-kit-genai-prompt-api/SKILL.md` (42dc227)
8. android/skills `device-ai/appfunctions/SKILL.md` (42dc227) — targetSdk 36·compileSdk 37 이상
9. 서울특별시 스마트도시정책관, 「고령층 친화 디지털 접근성 표준(모바일웹·앱, 영상콘텐츠)」, https://smart.seoul.go.kr/board/41/4901/board_view.do
10. 10대 지침 요약, https://weeklyuxuichallenge.oopy.io/1012cb39-7864-48c9-a153-b6467b21ce8e
11. Android Accessibility Help, 「Touch target size」, https://support.google.com/accessibility/android/answer/7101858
12. Android Developers, 「Android 14 features」, https://developer.android.com/about/versions/14/features
13. Toss Tech, 「토스의 8가지 라이팅 원칙들」, https://toss.tech/article/8-writing-principles-of-toss
