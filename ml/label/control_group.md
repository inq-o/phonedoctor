# 대조군 측정 절차 (T027)

설치 시점 분류기(SC-001)와 같은 테스트셋을 기존 수단 두 가지로 검사해 앱 단위 Recall·FPR을 비교한다. 정의는 spec §4 "오탐 지표 3종"의 **앱 단위 FPR**을 따른다. 증상 감지(SC-002)에는 같은 비교가 없다. 두 도구 모두 설치된 앱을 검사할 뿐, 앱 밖 광고 화면을 앱에 귀속하지 않기 때문이다. 그쪽 차별점(자동 귀속·가족 알림)은 T074 결과와 함께 서술로 적는다.

| 도구 | 무엇을 잡나 | 비교에 쓰는 이유 |
|---|---|---|
| 시티즌코난 (경찰청) | 전화사기 악성 앱, 원격제어 앱, 출처를 알 수 없는 앱 ([korea.kr](https://m.korea.kr/news/reporterView.do?newsId=148931162)) | 시니어에게 실제로 권하는 수단 (spec 문제 정의) |
| Google Play Protect | 기기 전체 검사로 유해 앱 경고 ([Google 도움말](https://support.google.com/android/answer/2812853), [기기 보호](https://developers.google.com/android/play-protect/client-protections)) | 기본으로 켜진 수단. 라벨 기준(README §1)도 Play Protect의 방해 광고 정의를 따름 |

## 1. 대상

- 테스트셋 `test_v1.csv`의 앱 중 라벨이 `adware`이거나 `weak`가 아닌 `normal`이고, README §3의 판 대조(`apk_versions.py`)에서 `ok`가 `yes`인 앱. 나머지는 분모에서 뺀다(`control_metrics.py`가 판 대조 결과를 받아 거르고 뺀 수를 알림). 판이 다른 앱은 대조군 결과를 분류기 결과와 비교할 수 없다.
- 2026-11-24 기준 v1 라벨 중 `ok`가 `yes`인 앱은 없다(README §3). 다시 관찰해 라벨을 확정한 뒤에 잰다.
- **관찰한 판과 같은 APK**를 설치한다(`ml/data/apk_split/`의 분할 세트).
- 분류기 결과와 같은 테스트셋 버전을 쓴다. 수치에는 `test_v1`처럼 버전을 함께 적는다.

## 2. 환경

- **대조군 전용 AVD `Control_API_35`**를 새로 만든다(관찰용과 같은 사양: Pixel 8, API 35, Google Play 이미지, arm64). 관찰용 `Observe_API_35`는 adb 설치 검사를 꺼 두었으므로(README §2) 쓰지 않는다.
- 시티즌코난이 에뮬레이터에서 실행되지 않거나 검사를 거부하면 그 도구만 시험용 S10+(Android 12)로 잰다. 실기기는 스냅숏이 없으므로 묶음마다 §3-1의 실기기 초기화를 거친다. 같은 도구는 한 기기에서만 재고, 기기 이름을 `device` 열에 적는다. 에뮬레이터와 실기기 결과는 비교표에 기기를 함께 적어 구분한다.
- 측정 전에 두 도구를 최신으로 올린 뒤 **Play 스토어 자동 업데이트를 끈다**(측정 중 판이 바뀌지 않게). 버전은 앱 정보에서 읽어 `tool_version`에 적는다. Play Protect는 Google Play 서비스(`com.google.android.gms`) 판과 Play 스토어 판을 함께 적는다(예: `gms 26.1 / store 45.0`).
- 네트워크를 켠다. Play Protect 검사는 Google 서버에서 최신 정보를 받는다.
- 측정 중에는 우리 앱(폰주치의)을 설치하지 않는다.

두 도구는 **서로 다른 깨끗한 상태에서 따로** 잰다. 한쪽이 설치를 막거나 앱을 지우면 다른 쪽의 분모가 바뀌기 때문이다(예: Play Protect가 adware 10개 중 5개를 막으면 시티즌코난은 남은 5개로만 재어져 Recall이 부풀려짐).

1회 준비 (스냅숏 둘):

```bash
emulator -avd Control_API_35 -no-audio -no-boot-anim
adb shell cmd alarm set-timezone Asia/Seoul
# Play 스토어 로그인, 시티즌코난 설치·필수 권한 허용, 한국어 1순위, Play 스토어 자동 업데이트 끄기

# Play Protect 측정용: adb 설치도 검사
adb shell settings put global verifier_verify_adb_installs 1
adb emu avd snapshot save clean_pp

# 시티즌코난 측정용: Play Protect가 설치를 막거나 앱을 지우지 않게 adb 설치 검사를 끄고,
# Play 스토어 → 프로필 → Play Protect → 설정에서 "Play Protect로 앱 검사"도 끈다
adb shell settings put global verifier_verify_adb_installs 0
adb emu avd snapshot save clean_conan
```

`verifier_verify_adb_installs` 설정이 실제로 Play Protect 설치 검사를 켜고 끄는지는 기기·판마다 다를 수 있으므로, 첫 묶음에서 확인한 결과(경고가 떴는지)를 결과 보고에 적는다.

## 3. 순서

앱을 한꺼번에 설치하면 경고가 어느 앱 것인지 헷갈리므로 **묶음 10개씩** 진행하고, 묶음 번호를 `batch` 열에 적는다. 도구마다 아래를 처음부터 끝까지 따로 돈다(Play Protect는 `clean_pp`, 시티즌코난은 `clean_conan`).

0. 묶음을 짤 때 aapt2로 앱 이름(라벨)을 뽑아, **같은 묶음 안에 이름이 같거나 비슷한 앱이 없게** 나눈다(클리너·배터리·손전등처럼 흔한 이름이 많음). 두 도구에 같은 묶음을 쓴다.
1. 그 도구의 스냅숏에서 시작한다: `adb emu avd snapshot load clean_pp` (또는 `clean_conan`)
2. 묶음의 앱 10개를 adb로 설치한다(사이드로드). 설치자를 지정하지 않는다. 테스트셋은 이렇게만 설치할 수 있으므로, 결과는 "adb 설치 앱 기준"이라고 보고에 적는다.
   ```bash
   adb install-multiple ml/data/apk_split/<패키지>/*.apk
   ```
3. **Play Protect** (`clean_pp`): 설치할 때 막거나 경고하면 그 자체가 탐지다 → `flagged`(문구 그대로). 설치를 마친 뒤 Play 스토어 → 프로필 → Play Protect → 검사. 경고가 뜬 앱, 검사 도중 꺼지거나 지워진 앱도 `flagged`다. 화면 문구를 그대로 적고 스크린숏을 남긴다. 설치 경고가 대화상자로 뜨는지 설치 실패 메시지로 끝나는지, 메뉴 이름이 무엇인지는 첫 측정 때 확인해 이 줄에 고쳐 적는다.
4. **시티즌코난** (`clean_conan`): 첫 화면의 [악성 앱 검사]. 결과에 나온 앱과 분류(전화사기 악성 앱·원격제어 앱·출처를 알 수 없는 앱 등)를 그대로 적고 스크린숏을 남긴다. 이 상태에서 설치가 실패하면 그 앱은 `not_scanned`(이유: 설치 실패 메시지)다. `not_scanned`는 도구가 검사하지 못한 경우에만 쓴다.
5. 두 도구 모두 결과를 앱 이름으로 보여 줄 수 있다. 결과 항목을 눌러 앱 정보 화면에서 **패키지명을 확인**하고 적는다. 이름만으로 짝짓지 않는다.
6. 스냅숏으로 되돌리고 다음 묶음으로 넘어간다.

### 3-1. 실기기 초기화 (실기기로 잴 때만)

스냅숏이 없으므로 묶음마다 아래를 확인한다. 하나라도 맞지 않으면 그 묶음을 다시 잰다.

1. 측정 전에 설치 앱 목록을 기준으로 저장한다: `adb shell pm list packages -3 | sort > ml/data/control/<날짜>/baseline.txt`
2. 묶음이 끝나면 그 묶음 앱을 모두 지운다: `adb uninstall <패키지>` (묶음 전체)
3. 다시 목록을 떠서 기준과 같은지 본다: `diff <(adb shell pm list packages -3 | sort) ml/data/control/<날짜>/baseline.txt`
4. 도구가 이전 검사 결과를 남기는지(지운 앱이 결과에 계속 보이는지) 첫 두 묶음에서 확인하고, 남기면 다음 묶음 전에 그 도구의 저장 데이터를 지운 뒤 권한을 다시 허용한다(`adb shell pm clear <도구 패키지>`).

스크린숏과 화면 문구는 `ml/data/control/<날짜>/<도구>/`에 둔다(레포 제외, 관찰 기록과 같은 방식).

## 4. 기록 양식

`control_v1.csv` — 앱·도구마다 한 줄. 쉼표가 든 문구는 따옴표로 감싼다(`csv.writer`). 정답 앱은 두 도구 모두 한 줄씩 있어야 집계한다.

| 열 | 값 |
|---|---|
| `package` | 패키지명 (앱 정보 화면에서 확인한 것) |
| `tool` | `citizen_conan` / `play_protect` |
| `tool_version` | 앱 버전 (Play Protect는 `gms <판> / store <판>`). 한 도구 안에서는 모두 같아야 함 |
| `device` | `Control_API_35` 또는 실기기 이름. 한 도구 안에서는 모두 같아야 함 |
| `batch` | 묶음 번호 (1부터) |
| `result` | `flagged`: 유해·악성·광고 경고, 설치 차단, 검사 중 끄기·지우기 / `source_only`: 설치 경로 경고뿐(예: 출처를 알 수 없는 앱) / `clean`: 경고 없음 / `not_scanned`: 그 도구가 검사하지 못함(설치 실패·검사 오류) |
| `warning` | 화면 문구 그대로(`not_scanned`이면 이유). `clean`이면 비움 |
| `scanned` | 검사 날짜 `YYYY-MM-DD`. 한 도구는 3일 안에 다 잰다(서버 쪽 정보가 날마다 바뀔 수 있음). 집계는 도구별 날짜 범위를 함께 낸다 |

**`source_only`를 따로 두는 이유.** 테스트셋은 adb로 설치하므로 앱이 실제로 광고 도배인지와 상관없이 "출처를 알 수 없는 앱"으로 나올 수 있다. 이런 결과를 `flagged`로 세면 두 도구의 Recall과 FPR이 함께 부풀려진다. 그래서 설치 경로 경고는 잡지 못한 것으로 센다. `source_only` 개수는 따로 보고한다. 한 앱에 여러 경고가 함께 뜨면 가장 강한 것을 고른다(`flagged` > `source_only`).

## 5. 집계

```bash
python ml/label/apk_versions.py ml/label/test_v1_labels.csv > ml/data/control/versions.csv || true   # ok가 yes 아닌 앱이 있으면 exit 1이지만 CSV는 씀
python ml/label/control_metrics.py ml/label/test_v1_labels.csv ml/label/test_v1.csv ml/data/control/versions.csv ml/label/control_v1.csv
```

라벨·대조군·판 대조 기록의 형식 오류(중복 포함), 한 도구 안의 판·기기 섞임, 한 도구의 검사 날짜가 3일을 넘음, 판 대조 결과와 라벨 파일의 앱 불일치, 기록이 빠진 정답 앱이 있으면 수치를 내지 않고 멈춘다. 통과하면 도구별로 Recall(잡은 adware ÷ adware)과 앱 단위 FPR(잡은 normal ÷ normal)을 내고, 검사 못 한 수(분모에서 뺌)와 설치 경로 경고만 뜬 수를 함께 낸다. 분류기 수치도 같은 정답 앱 집합으로 낸다. 보고서 비교표는 다음과 같이 만든다.

| 수단 | 테스트셋 | Recall | 앱 단위 FPR | 검사 못 함 | 설치 경로 경고만 |
|---|---|---|---|---|---|
| 폰주치의 설치 시점 분류기 | test_v1 | | | | — |
| 시티즌코난 (판) | test_v1 | | | | |
| Play Protect (Play 스토어 판) | test_v1 | | | | |

## 6. 한계

- 두 도구의 탐지 기준은 공개되지 않았고, 서버 쪽 정보가 바뀌면 같은 앱도 다른 날 다르게 나올 수 있다. 그래서 검사 날짜와 도구 판을 함께 적는다.
- 시티즌코난은 전화사기·원격제어 앱을 겨냥한 도구라, 광고 도배 앱을 잡지 못하는 것이 결함이라기보다 용도의 차이일 수 있다. 보고서에서는 "광고 도배 앱에 대해서는"이라는 범위를 붙여 쓴다.
- 에뮬레이터에서 측정하면 Play Protect의 기기 인증 상태가 실기기와 다를 수 있다. 실기기로 바꾸면 그 사실을 적는다.
