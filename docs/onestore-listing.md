# 원스토어 등록 준비 (T062·T063)

조사일 2026-11-24. 근거는 [ONEconsole 개발자 가이드](https://onestore-dev.gitbook.io/dev)와 [Android 개발자 인증](https://developer.android.com/developer-verification). 등록 직전에 바뀐 곳이 없는지 다시 본다.

## 1. 심사(검증) 기준 점검 (T062)

| 기준 | 가이드 내용 | 폰주치의 | 판단 |
|---|---|---|---|
| 개인정보처리방침 | "개인정보 처리방침을 공개하여야 하며" ([개인정보 보호·보안](https://onestore-dev.gitbook.io/dev/docs/review/one-store-review-guideline/undefined-2.md)) | [공개함](https://inq-o.github.io/phonedoctor/privacy-policy.html) | 충족 |
| 접근권한 최소화 | "접근권한을 서비스에 필요한 범위 내로 최소화" (같은 곳). 권한별 제한 목록은 없고 "불필요한 퍼미션 허용은 검증에서 불이익" ([상품 FAQ](https://onestore-dev.gitbook.io/dev/help/faq/apps.md)) | 출시 빌드 권한 8개, 인터넷 권한 없음. 권한마다 아래 3절 설명을 붙임 | 충족(설명 필수) |
| 법정 고지·동의 | 개인정보를 수집하면 항목·목적·보유기간·거부권을 알리고 명시적 동의 (개인정보 보호·보안) | 출시 빌드는 기기 밖 전송이 없음. 첫 설정에서 읽는 정보와 이유를 안내함 | 해당 없음(가족 연결을 켤 때 다시 점검) |
| 수집정보 선언 | 기기 밖으로 전송·공유하면 "예" ([수집정보](https://onestore-dev.gitbook.io/dev/docs/apps/product/common-info/main-info/data.md)) | 출시 빌드는 "아니오" | 가족 연결을 켜는 판에서 "예"로 바꾸고 방침 URL 입력 |
| 악성행위·사용자 기만 | 스파이웨어, "사용자를 속이는 모든 코드 또는 행위"(피싱·사기광고) 금지 (개인정보 보호·보안) | 앱 삭제는 안드로이드 삭제 화면에서 사용자가 직접 확인. 겁주기 문구 없음 | 충족 |
| 부적절한 광고 | "앱 영역을 벗어나거나 사용자의 불편을 초래하는 광고는 허용되지 않습니다" ([앱기능](https://onestore-dev.gitbook.io/dev/docs/review/one-store-review-guideline/undefined-7.md)) | 광고 없음 | 충족 |
| 메타데이터 | 관련 없거나 오해의 소지가 있는 메타데이터 불가 ([상품 등록정보](https://onestore-dev.gitbook.io/dev/docs/review/one-store-review-guideline/undefined-5.md)) | 설명에 탐지율·"완벽 차단" 같은 표현을 쓰지 않음(4절) | 문구 점검 |
| 바이너리 | Private Key 서명, VersionCode 올리기 (상품 등록정보) | 우리 릴리스 키로 서명 | 충족 |
| 모니터링·가족 공유 앱 | 전용 반려 기준 없음(찾지 못함). 일반 조항(스파이웨어 금지)만 | 출시 빌드는 가족 연결 꺼짐 | spec Q5는 가족 연결을 켤 때 다시 확인 |
| 보안·클리너 앱 | 전용 기준 없음(찾지 못함) | — | 일반 조항(기만 금지·메타데이터)으로 판단 |

- 주요 반려 사유([검증 FAQ](https://onestore-dev.gitbook.io/dev/help/faq/review.md)): 앱 실행 불가, 타 앱 마켓 링크, 인앱결제·외부결제, 개인정보 정책 미준수. 앱 안에 Play 스토어 링크가 없는지 등록 전에 확인한다.
- 검증 기간은 가이드에 없다. 시스템 검증 뒤 가이드라인 검증, 반려는 메일로 온다([검증절차](https://onestore-dev.gitbook.io/dev/docs/review.md)).
- ONEconsole 2단계 인증 필수(2026-10-30부터).

## 2. 등록 입력 항목

| 항목 | 요건 | 준비 |
|---|---|---|
| 상품제목 | 50자 이내, 상표권 있는 이름 불가 | 폰주치의 |
| 패키지 네임 | 등록 뒤 변경 불가 | `io.github.inq_o.phonedoctor` |
| 한줄설명 | 100자 이내 | 4절 |
| 상품설명 | 1,300자 이내 | 4절 |
| 대표 아이콘 | 512×512 JPG/PNG | `docs/onestore/icon-512.png` (앱 아이콘과 같은 모양) |
| 그래픽 이미지 | 1024×578 JPG/PNG | `docs/onestore/graphic-1024x578.png` |
| 스크린샷 | 2~8장, 1MB 이내, 1,300px 이하, 권장 세로 720×1280 | `docs/onestore/screenshot-1~4.png` (606×1280, 출시 빌드를 S10+에서 캡처: 확인 필요 홈, 앱 확인, 지운 뒤, 정상 홈) |
| 키워드 | 1~10개 | 광고 앱, 광고 차단, 시니어, 부모님 폰, 휴대폰 검사, 앱 삭제, 광고 알림 |
| 권한설명 | 권한과 사용 목적, 설치 전 사용자에게 보임 | 3절 |
| 연령등급 | IARC 설문 + 비게임은 "ONE store 앱 연령등급" 추가 ([연령등급](https://onestore-dev.gitbook.io/dev/docs/apps/product/common-info/age-rating.md)) | 전체이용가 예상(폭력·성적 내용·결제 없음) |
| 판매자 정보 | 공식 E-mail·전화·웹사이트(필수 여부 미확인) | 메일은 방침과 같은 것, 웹사이트는 GitHub Pages |
| 서명 | 5가지 중 선택 ([App 서명](https://onestore-dev.gitbook.io/dev/docs/apps/product/android/app-signing.md)) | **"ONE store 앱 서명 선택해제(서명한 APK 등록)"**: App Check에 등록할 SHA-256과 Android 개발자 인증의 키 소유권 증명이 우리 키 하나로 끝남. AAB로 한 번 바꾸면 APK로 못 돌아감 |

## 3. 권한설명 초안

출시 빌드 병합 매니페스트 기준(2026-11-24). 인터넷 권한은 없다.

| 권한 | 설명(등록 화면에 그대로) |
|---|---|
| 모든 앱 조회 (`QUERY_ALL_PACKAGES`) | 광고를 띄운 앱이 휴대폰에 설치된 어느 앱인지 찾고, 그 앱의 이름과 아이콘을 보여 주기 위해 사용합니다. 앱 목록은 휴대폰 밖으로 보내지 않습니다. |
| 사용 기록 접근 (`PACKAGE_USAGE_STATS`) | 사용자가 쓰지 않았는데 갑자기 화면에 뜬 앱을 찾기 위해 사용합니다. 사용자가 설정에서 직접 켜야 하며, 화면 안의 내용은 볼 수 없고 앱 이름과 시각만 읽습니다. |
| 앱 삭제 요청 (`REQUEST_DELETE_PACKAGES`) | 광고를 띄우는 앱을 지울 때 안드로이드 앱 삭제 화면을 열기 위해 사용합니다. 사용자가 그 화면에서 확인해야 지워집니다. |
| 알림 표시 (`POST_NOTIFICATIONS`) | 확인이 필요한 앱을 찾았을 때 알려 드리기 위해 사용합니다. |
| 부팅 완료 수신 (`RECEIVE_BOOT_COMPLETED`) | 휴대폰을 다시 켠 뒤에도 정기 검사를 이어 가기 위해 사용합니다. |
| 포그라운드 서비스·절전 해제·네트워크 상태 (`FOREGROUND_SERVICE`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE`) | 정기 검사를 예약하고 마치기 위해 안드로이드 작업 관리 기능(WorkManager)이 사용합니다. 인터넷으로 정보를 보내지 않습니다. |

## 4. 설명 문구 초안

출시 빌드에 있는 기능만 적는다. 설치 시점 판정(T050)을 붙이면 고친다.

한줄설명 (100자 이내)

> 부모님 휴대폰에 갑자기 뜨는 광고, 어느 앱이 띄우는지 찾아 알려 드려요.

상품설명 (1,300자 이내)

> 휴대폰을 쓰지 않는데 광고 화면이 갑자기 뜨나요? 폰주치의는 그 광고를 띄우는 앱을 찾아 알려 드립니다.
>
> ■ 이런 분께 필요해요
> - 부모님 휴대폰에 자꾸 광고가 뜬다는 이야기를 들은 가족
> - 어떤 앱 때문인지 몰라 휴대폰을 초기화하려던 분
>
> ■ 이렇게 찾아요
> - 사용자가 휴대폰을 만지지 않았는데 화면에 뜬 앱을 사용 기록으로 찾습니다.
> - "○○ 오늘 광고를 3번 띄웠어요"처럼 앱 이름과 횟수로 알려 드립니다.
> - [지우기]를 누르면 안드로이드 앱 삭제 화면이 열립니다. 지울지는 직접 정하세요.
>
> ■ 쓰기 쉽게 만들었어요
> - 홈은 큰 칸 세 개(살펴보기·검사 기록·설정)로, 누를 곳을 찾기 쉽습니다.
> - 글자는 휴대폰의 글자 크기 설정을 따라 커집니다.
> - 처음 설정할 때 필요한 권한을 하나씩 안내합니다.
>
> ■ 개인정보
> - 인터넷 권한이 없습니다. 읽은 정보는 휴대폰 밖으로 나가지 않습니다.
> - 광고와 분석 도구가 없고, 회원가입도 없습니다.
> - 사진, 문자, 연락처, 위치, 화면 내용은 읽지 않습니다.
>
> ■ 알아 두세요
> - 사용 기록 권한을 켜야 검사할 수 있습니다.
> - 앱 안에서만 보이는 광고(동영상 앱의 광고 등)는 찾는 대상이 아닙니다.
> - 모든 광고 앱을 찾는다고 보장하지는 않습니다. 이상한 앱을 찾으면 지우기 전에 한 번 더 확인하세요.

## 5. Android 개발자 인증 (T063)

| 질문 | 확인 내용 |
|---|---|
| 한국 시행 | 2026-09-30 첫 시행국은 브라질·인도네시아·싱가포르·태국. 한국은 "2027년 전 세계 확대"에 들어가며 날짜는 아직 없음 ([개발자 인증](https://developer.android.com/developer-verification)) |
| 원스토어 | 첫 시행 대상 스토어 목록(Play·Galaxy Store·Xiaomi 등)에 원스토어는 없음. "곧 모든 서드파티 스토어로 확대" ([가이드](https://developer.android.com/developer-verification/guides)) |
| 원스토어 안내 | 바이너리 등록 때 "Android 개발자 인증 대상 여부 확인" 팝업은 안내일 뿐 등록·검수·출시에 영향 없음. 원스토어는 인증 여부를 조회하지 않고 `assets/adi-registration.properties` 포함 여부만 봄 ([원스토어 FAQ](https://onestore-dev.gitbook.io/dev/help/faq/apps/developer-verification.md)) |
| 개발자가 할 일 | Android Developer Console 가입 → 본인 인증(개인: 정부 발급 사진 신분증·주소 증빙, 수수료 25달러) → 패키지 이름과 서명 키 SHA-256 등록 → 콘솔이 주는 내용을 `assets/adi-registration.properties`에 넣어 우리 키로 서명한 APK 업로드 ([정식 배포 계정](https://developer.android.com/developer-verification/guides/full-distribution), [FAQ](https://developer.android.com/developer-verification/guides/faq)) |
| 무료 계정 | "제한 배포" 계정은 무료·신분증 불필요지만 상업 목적이 없어야 하고 기기 수 제한(20대 또는 50대, 문서마다 다름). 스토어 배포에는 맞지 않음. 제한 → 정식 전환은 가능 ([제한 배포](https://developer.android.com/developer-verification/guides/limited-distribution)) |
| 미인증 앱 | 시행 지역·대상 스토어에서 설치·업데이트 차단. ADB 설치는 인증 없이 가능 |

결론

- 과제 기간(2026년 안 검증 승인·최종 보고)에는 영향이 없다. 한국·원스토어 모두 아직 시행 대상이 아니다.
- 2027년 한국 시행 전에 정식 배포 계정(25달러, 본인 인증)으로 패키지를 등록한다. 서명을 우리 키로 유지하면(2절) 키 소유권 증명이 간단하다.
- 시행 날짜가 나오면 이 절을 고친다.

## 6. 사용자가 할 일 (올리기 직전)

1. **릴리스 서명 키 만들기** (한 번만, 터미널에서 직접. 비밀번호는 본인만 알고 아무에게도 알려 주지 않음)
   ```zsh
   mkdir -p ~/.phonedoctor && chmod 700 ~/.phonedoctor
   keytool -genkeypair -keystore ~/.phonedoctor/phonedoctor-release.jks -alias phonedoctor -keyalg RSA -keysize 4096 -validity 10000
   read -rs "P?방금 정한 키 비밀번호: "; echo; printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=phonedoctor\nkeyPassword=%s\n' ~/.phonedoctor/phonedoctor-release.jks "$P" "$P" > ~/.phonedoctor/release.properties; chmod 600 ~/.phonedoctor/release.properties; unset P
   ```
   - 이름·조직 질문은 아무 값이나 넣어도 됨(예: 이름에 본인 이름)
   - 비밀번호에 `\` 문자는 쓰지 않음(설정 파일이 이스케이프로 읽음)
   - **키 파일과 비밀번호를 따로 백업**(비밀번호 관리자 등). 잃으면 앱을 더는 업데이트할 수 없음
2. **서명된 APK 빌드**: `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (`app-release-unsigned.apk`가 나오면 1번 설정 파일을 못 찾은 것)
   - 서명 확인: `~/Library/Android/sdk/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` → 서명자 이름과 SHA-256(4번에 씀)이 나오면 됨
3. **ONEconsole**
   - [ ] 2단계 인증 켜기
   - [ ] 신규 상품 등록: 제목 "폰주치의", 패키지 `io.github.inq_o.phonedoctor`
   - [ ] 앱 서명: **"ONE store 앱 서명 선택해제(서명한 APK 등록)"**
   - [ ] 판매정보: 4절 한줄설명·상품설명, 3절 권한설명, 키워드, 아이콘·그래픽·스크린샷(`docs/onestore/`)
   - [ ] 수집정보: 정보 수집·공유 "아니오"(출시 빌드는 인터넷 권한 없음), 개인정보처리방침 URL https://inq-o.github.io/phonedoctor/privacy-policy.html
   - [ ] 연령등급 설문(전체이용가 예상)
   - [ ] 바이너리 `app-release.apk` 등록 → 검증 요청
4. (나중에) 릴리스 키 SHA-256을 Firebase에 등록(가족 연결을 출시 빌드에서 켤 때), 2027년 전 Android Developer Console 정식 배포 계정과 패키지 등록
