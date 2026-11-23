# 대조군 측정 절차 (T027)

설치 시점 분류기(SC-001)와 같은 테스트셋을 기존 수단 두 가지로 검사해 앱 단위 Recall·FPR을 비교한다. 정의는 spec §4 "오탐 지표 3종"의 **앱 단위 FPR**을 따른다. 증상 감지(SC-002)에는 같은 비교가 없다. 두 도구 모두 설치된 앱을 검사할 뿐, 앱 밖 광고 화면을 앱에 귀속하지 않기 때문이다. 그쪽 차별점(자동 귀속·가족 알림)은 T074 결과와 함께 서술로 적는다.

| 도구 | 무엇을 잡나 | 비교에 쓰는 이유 |
|---|---|---|
| 시티즌코난 (경찰청) | 전화사기 악성 앱, 원격제어 앱, 출처를 알 수 없는 앱 ([korea.kr](https://m.korea.kr/news/reporterView.do?newsId=148931162)) | 시니어에게 실제로 권하는 수단 (spec 문제 정의) |
| Google Play Protect | 기기 전체 검사로 유해 앱 경고 ([Google 도움말](https://support.google.com/android/answer/2812853), [기기 보호](https://developers.google.com/android/play-protect/client-protections)) | 기본으로 켜진 수단. 라벨 기준(README §1)도 Play Protect의 방해 광고 정의를 따름 |

## 1. 대상

- 테스트셋 `test_v1.csv`의 앱 중 라벨이 `adware`이거나, `weak`가 아닌 `normal`인 앱. `review`·`excluded`·`weak normal`은 분모에서 뺀다(`control_metrics.py`가 자동으로 거름).
- **관찰한 판과 같은 APK**를 설치한다(`ml/data/apk_split/`의 분할 세트, README §3의 SHA-256 대조에서 `ok`가 `yes`인 앱). 판이 다른 앱은 대조군 결과도 분류기 결과와 비교할 수 없다.
- 분류기 결과와 같은 테스트셋 버전을 쓴다. 수치에는 `test_v1`처럼 버전을 함께 적는다.

## 2. 환경

- 관찰 전용 AVD `Observe_API_35`(README §2)를 쓴다. Google Play 이미지라 Play Protect가 있다.
- 시티즌코난이 에뮬레이터에서 실행되지 않거나 검사를 거부하면 시험용 S10+(Android 12)를 쓰고, 기기를 결과 파일에 적는다. 같은 도구는 한 환경에서만 잰다.
- 측정 전에 두 도구를 최신으로 올리고, 앱 정보에서 버전 이름을 `tool_version`에 적는다. Play Protect는 Play 스토어 버전을 적는다.
- 네트워크를 켠다. Play Protect 검사는 Google 서버에서 최신 정보를 받는다.
- 측정 중에는 우리 앱(폰주치의)을 설치하지 않는다.

## 3. 순서

앱을 한꺼번에 설치하면 경고가 어느 앱 것인지 헷갈리므로 **묶음 10개씩** 진행한다.

1. 스냅숏 `clean`(도구 두 개만 설치, 권한 허용 마침)에서 시작한다.
2. 묶음의 앱 10개를 설치한다.
   ```bash
   adb install-multiple ml/data/apk_split/<패키지>/*.apk
   ```
   설치할 때 Play Protect가 막거나 경고하면, 그 앱의 `play_protect` 결과를 그 문구로 적는다. "그래도 설치"로 넘기면 묶음 검사도 계속할 수 있다.
3. **Play Protect**: Play 스토어 → 프로필 → Play Protect → 검사. 경고가 뜬 앱마다 화면 문구를 그대로 적고 스크린숏을 남긴다. 메뉴 이름은 Play 스토어 판마다 다를 수 있으므로, 첫 측정 때 실제 경로를 이 줄에 고쳐 적는다.
4. **시티즌코난**: 첫 화면의 [악성 앱 검사]. 결과에 나온 앱과 분류(전화사기 악성 앱·원격제어 앱·출처를 알 수 없는 앱 등)를 그대로 적고 스크린숏을 남긴다.
5. 스냅숏 `clean`으로 되돌리고 다음 묶음으로 넘어간다.

스크린숏과 화면 문구는 `ml/data/control/<날짜>/<도구>/`에 둔다(레포 제외, 관찰 기록과 같은 방식).

## 4. 기록 양식

`control_v1.csv` — 앱·도구마다 한 줄. 쉼표가 든 문구는 따옴표로 감싼다(`csv.writer`).

| 열 | 값 |
|---|---|
| `package` | 패키지명 |
| `tool` | `citizen_conan` / `play_protect` |
| `tool_version` | 앱 버전 이름 (Play Protect는 Play 스토어 버전) |
| `result` | `flagged`: 유해·악성·광고 경고 / `source_only`: 설치 경로 경고뿐(예: 출처를 알 수 없는 앱) / `clean`: 경고 없음 / `not_scanned`: 설치 실패·검사 오류 |
| `warning` | 화면 문구 그대로. `flagged`·`source_only`이면 필수, `clean`이면 비움 |
| `scanned` | 검사 날짜 `YYYY-MM-DD` |

**`source_only`를 따로 두는 이유.** 테스트셋은 adb로 설치하므로 앱이 실제로 광고 도배인지와 상관없이 "출처를 알 수 없는 앱"으로 나올 수 있다. 이런 결과를 `flagged`로 세면 두 도구의 Recall과 FPR이 함께 부풀려진다. 그래서 설치 경로 경고는 잡지 못한 것으로 센다. `source_only` 개수는 따로 보고한다. 한 앱에 여러 경고가 함께 뜨면 가장 강한 것을 고른다(`flagged` > `source_only`).

## 5. 집계

```bash
python ml/label/control_metrics.py ml/label/test_v1_labels.csv ml/label/test_v1.csv ml/label/control_v1.csv
```

형식 오류가 있으면 멈춘다. 오류가 없으면 도구별로 Recall(잡은 adware ÷ adware)과 앱 단위 FPR(잡은 normal ÷ normal)을 낸다. `not_scanned`는 분모에서 빼고 개수를 따로 적는다. 기록이 빠진 정답 앱도 알린다. 보고서 비교표는 다음과 같이 만든다.

| 수단 | 테스트셋 | Recall | 앱 단위 FPR | 검사 못 함 | 설치 경로 경고만 |
|---|---|---|---|---|---|
| 폰주치의 설치 시점 분류기 | test_v1 | | | | — |
| 시티즌코난 (판) | test_v1 | | | | |
| Play Protect (Play 스토어 판) | test_v1 | | | | |

## 6. 한계

- 두 도구의 탐지 기준은 공개되지 않았고, 서버 쪽 정보가 바뀌면 같은 앱도 다른 날 다르게 나올 수 있다. 그래서 검사 날짜와 도구 판을 함께 적는다.
- 시티즌코난은 전화사기·원격제어 앱을 겨냥한 도구라, 광고 도배 앱을 잡지 못하는 것이 결함이라기보다 용도의 차이일 수 있다. 보고서에서는 "광고 도배 앱에 대해서는"이라는 범위를 붙여 쓴다.
- 에뮬레이터에서 측정하면 Play Protect의 기기 인증 상태가 실기기와 다를 수 있다. 실기기로 바꾸면 그 사실을 적는다.
