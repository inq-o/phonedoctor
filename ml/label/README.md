# 라벨링·테스트셋 (T026)

직접 수집한 앱에 "광고 도배" 정답을 붙이고, 평가용 테스트셋을 버전별로 동결한다. 라벨은 특징(권한·SDK·이름)이 아니라 **에뮬레이터에서 실제로 본 행동**에서만 나온다. 특징으로 라벨을 만들면 모델이 규칙을 외워 평가가 부풀려진다.

## 1. 판정 기준

Play Protect "Disruptive ads" 정의와 Play 정책의 "예상치 못한 전면 광고" 기준을 따른다. 광고 화면은 **앱 밖에서 보일 때만** 광고 도배로 친다. 같은 전면 광고라도 백그라운드 앱이 띄우면 R1, 사용 중인 앱 안에서 뜨면 R6. R2~R5는 광고 화면이 아닌 다른 방해 행동이다.

| 코드 | 관찰 기준 | 판정 |
|---|---|---|
| R1 | 앱 밖에서 광고 표시 (홈 화면, 다른 앱 위, 잠금화면) | 광고 도배 |
| R2 | 광고성 알림 발송 | 광고 도배 |
| R3 | 닫기 어려운 광고 (닫기 버튼 숨김·지연, 뒤로가기 막힘) | 광고 도배 |
| R4 | 삭제 방해 (기기 관리자 요구, 아이콘 숨김) | 광고 도배 |
| R5 | 가짜 경고("바이러스 발견", "메모리 90%")로 광고·결제 유도 | 광고 도배 |
| R6 | 앱을 쓰는 도중 앱 안에서 예고 없이 뜨거나 건너뛸 수 없는 전면 광고 (앱 실행 직후, 작업 도중, 화면 전환마다) | 정상 (`R6`로 표시만). 앱 밖 광고가 아니라 막을 대상이 아님(2026-10-14 기준). 증상 감지 오탐 평가(T074)에 쓸 정상 사례 |
| — | 자연스러운 멈춤 지점(게임 오버, 단계 클리어, 작업 완료)에서 뜨고 건너뛸 수 있는 전면 광고 | 정상 |
| — | 앱 안에서만 보이는 배너·네이티브 광고 | 정상 |
| — | 설치·실행이 안 되거나 관찰 중 화면을 판단할 수 없음 | 제외 (`excluded`) |

**용도와 단위.** 이 라벨은 앱(APK) 하나에 붙는 설치 시점 판정용 정답이다. R1~R5 중 하나라도 보이면 `adware`다. 증상 감지(US11)가 잡는 것은 앱 밖 광고 화면 전환뿐이다. 그래서 증상 감지 recall의 양성 앱으로는 `R1`이 붙은 앱만 쓰고, R2~R5만 있는 앱은 쓰지 않는다. `R1`이라도 다른 앱 위 오버레이 창으로만 보인 앱(사용 기록에 화면 전환이 남지 않음)은 recall 분모에서 빼고 커버리지로 따로 센다(spec G2·SC-002). 오버레이형인지는 `evidence`에 적는다. 증상 감지의 정답과 오탐률 분모는 이벤트 단위라 T074에서 따로 붙인다. 이 CSV의 앱 단위 개수를 이벤트 분모로 쓰지 않는다.

## 2. 관찰 절차

관찰 전용 AVD `Observe_API_35` (Pixel 8, API 35, Google Play 이미지, arm64, 4GB)를 쓴다. 개발용 AVD와 섞지 않는다.

1회 준비 (AVD를 새로 만들었을 때만):

```bash
emulator -avd Observe_API_35 -no-snapshot-save -no-audio -no-boot-anim
adb shell svc power stayon true
adb shell settings put global verifier_verify_adb_installs 0   # adb 설치 검사 끔. Play Protect 판정은 대조군(T027)에서 따로 잰다
adb shell cmd alarm set-timezone Asia/Seoul
adb shell locksettings set-disabled false   # 스와이프 잠금. 잠금화면 광고(R1)를 보려면 필요
# 언어: 설정 > 시스템 > 언어에서 한국어(대한민국)를 1순위로
```

앱마다:

```bash
ml/label/observe.sh <package> ml/data/apk_split/<package>[.apk]
```

- 설치는 `-g`로 런타임 권한을 모두 허용한다(시니어가 권한 창을 대개 허용하는 상황을 가정).
- 특수 권한은 앱이 매니페스트에 선언한 것만 adb로 켠다: 모든 파일 접근, 다른 앱 위에 표시, 사용 기록 접근, 정확한 알람, 시스템 설정 변경, 앱 설치, 배터리 최적화 제외. 켜지 않으면 monkey가 설정 화면으로 못 나가 "권한이 필요합니다" 창에서 맴돈다. 켠 목록은 `granted.txt`. 접근성·알림 접근·기기 관리자는 여러 단계 동의가 필요해 켜지 않는다.
- 사용 중에는 알림창이 내려오지 않게 막는다(monkey가 알림창 안에 갇힘). 알림 표시는 막지 않는다.
- 사용 3분: `monkey` 무작위 입력(0.7초 간격, 시드 고정, 대상 앱 안에서만)을 180초 동안. 화면 녹화 `use.webm`(호스트 쪽 녹화라 캡처 방지 앱도 찍힘).
- 홈 대기 10분: 1분마다 화면·현재 포커스 창·대상 앱 소유 창 기록. 끝에 잠금화면 캡처, 알림·기기 관리자·앱 권한(appops) 덤프.
- 기기 관리자를 해제하고 앱을 지운다.

표본 전체는 `ml/label/observe_all.sh ml/label/test_v1.csv [출력 폴더]`로 차례로 관찰한다. 앱이 기기에 남아 있거나 남았는지 확인하지 못하면(연결 끊김 등) 다음 앱을 시작하지 않고 exit 2로 멈춘다. 관찰에 실패한 앱은 exit 1로 알리고, 다시 실행하면 그 앱만 다시 관찰한다. 실패 처리는 `bash ml/label/test_observe_all.sh`로 검사한다(가짜 adb 사용, 기기 불필요).

결과는 `ml/data/observe/<package>/`에 남는다(레포 제외). 판정 근거로 쓰는 파일은 다음과 같다.

| 파일 | 근거 |
|---|---|
| `bg_*.png`, `lock.png`, `focus.log`, `windows.log` | R1 (앱 밖·오버레이·잠금화면) |
| `notifications.txt`, `notifications.png` | R2 |
| `use.webm`, `use_*.png` | R3·R5·R6 |
| `device_admin.txt`, `meta.txt`의 `launcher=` | R4 |

## 3. 라벨 기록

`test_v1_labels.csv` — 패키지마다 한 줄. `csv.writer`로 쓰고(쉼표가 든 근거는 따옴표로 감쌈), 고친 뒤에는 검사한다.

```bash
python ml/label/check_labels.py ml/label/test_v1_labels.csv ml/label/test_v1.csv
```

| 열 | 값 |
|---|---|
| `package` | 패키지명 |
| `label` | `adware` / `normal` / `excluded` / `review`(판정 보류, 정답으로 쓰지 않음) |
| `codes` | 해당 코드 `R1;R3`. `adware`는 R1~R5 중 하나 이상, `normal`은 `R6`만 쓸 수 있음. 해당 여부가 불확실하면 `R1?`처럼 `?`를 붙임(`review`에서만) |
| `confidence` | 비움 또는 `weak`(관찰이 짧거나 화면이 막혀 근거가 약함). `weak`인 `normal`은 확정 음성 정답으로 섞지 않음 |
| `evidence` | 근거 파일과 한 줄 설명 (예: `bg_04.png 홈 화면 전면 광고`) |
| `observed` | 관찰 날짜 `YYYY-MM-DD` |

라벨은 **관찰한 판**의 정답이다(`meta.txt`의 `version_code`·`apk_sha256`). 특징을 뽑은 APK(`ml/data/apk/`)는 먼저 받은 것이라 판이 다를 수 있다. 설치 시점 분류기 평가에 쓰기 전에 아래 세 가지를 대조한다: 특징 파일의 SHA-256과 특징 입력 APK, 특징 입력 APK와 관찰한 base APK(SHA-256·versionCode), 관찰 기록의 SHA-256과 지금 파일. 다른 앱은 같은 판을 다시 받아 특징을 뽑거나 평가에서 뺀다. 입력 APK를 바꾸면 MH-1M 대조(§4)도 새 입력으로 다시 한다.

`ok`가 `yes`인 라벨만 설치 시점 분류기 평가에 쓴다. 관찰 기록은 분할 APK 세트 전체를 대조하고, 라벨이 그 관찰에서 나왔는지(끝까지 마친 관찰, 관찰 날짜 = 라벨 `observed`)도 확인한다. 같은 앱을 다시 관찰하면 이전 라벨은 `no`가 되므로 새 증거로 라벨을 다시 붙인다. 관찰 기록에 SHA-256이 없으면 지금 파일이 맞아도 관찰 당시 APK와 같다고 확인할 수 없어 `unverified`로 두고, SHA-256을 기록하며 다시 관찰할 때까지 평가에서 뺀다.

v1 라벨 40행(2026-10-30): 7개가 `no`(versionCode가 다른 5개, versionCode는 같은데 base APK 파일이 다른 2개), 나머지 33개는 SHA-256 기록 전에 관찰해 `unverified`다. 지금은 설치 시점 평가에 쓸 수 있는 v1 라벨이 없다.

```bash
python ml/label/apk_versions.py ml/label/test_v1_labels.csv   # 다른 앱이 있으면 exit 1
```

**다시 관찰할 때.** v1 라벨과 `ml/data/observe/`는 지우거나 덮어쓰지 않는다. 지금 관찰 기록에 SHA-256을 소급해 적지도 않는다.

1. 새 실행은 새 폴더에 남긴다. `observe_all.sh`는 준 폴더 안의 기록만 보고 건너뛰므로 기존 `done` 때문에 건너뛰지 않는다. 같은 폴더를 다시 주는 것은 끊긴 그 실행을 이어갈 때뿐이다. 같은 날 다시 관찰하면 `<날짜>-2`처럼 이름을 달리한다. APK 입력 폴더(`ml/data/apk_split`) 안은 출력 폴더로 쓸 수 없다(exit 2).
2. 그 관찰로 붙인 라벨은 새 파일에 적는다. 같은 날짜를 폴더와 파일 이름에 써서 짝을 맞춘다.
3. 대조할 때 그 폴더를 지정한다. 실행을 끝까지 마쳤는지, 라벨의 `observed`가 실행 날짜와 같은지도 함께 확인한다.
4. 특징 입력을 관찰한 base로 바꿀 때도 기존 `ml/data/apk/`·`apk_features.csv.gz`를 덮어쓰지 않는다. 새 판으로 따로 두고 MH-1M 대조(§4)를 다시 한다. 대조 도구가 새 판을 읽게 하는 일은 그때 함께 한다.

```bash
ml/label/observe_all.sh ml/label/test_v1.csv ml/data/observe_runs/<날짜>
python ml/label/check_labels.py ml/label/test_v1_labels_<날짜>.csv ml/label/test_v1.csv
python ml/label/apk_versions.py ml/label/test_v1_labels_<날짜>.csv ml/data/observe_runs/<날짜>
```

## 4. 테스트셋 규칙

1. **버전으로 쌓는다.** `test_v1`(150개) → `test_v2`(+추가분). 한 번 넣은 앱은 빼거나 바꾸지 않는다. 수치에는 어느 버전 기준인지 함께 적는다.
2. **추가분도 무작위로 뽑는다.** 모델이 틀린 앱을 골라 넣지 않는다. 어려운 사례는 별도 도전셋으로 두고 대표 수치에 섞지 않는다.
3. **임계값은 테스트셋에서 정하지 않는다.** FPR 5% 지점은 검증셋에서 정하고, 테스트셋에서는 측정만 한다.
4. **학습 데이터와 겹치지 않게 한다.** 테스트셋 앱은 학습에 쓰지 않는다. MH-1M에는 패키지명이 없어 SHA-256으로만 대조한다(v1: 436개 중 겹침 0, 2026-10-12). 같은 앱의 다른 버전이 MH-1M에 있는지는 확인할 수 없다.

## 5. 표본

```bash
python ml/label/sample_testset.py ml/collect/packages.csv ml/data/apk ml/label/test_v1.csv
```

받은 APK 436개 중 회색지대 100 + 시니어 50, 시드 20261012. 광고 도배가 회색지대에 몰릴 것으로 보고 회색지대를 더 뽑았다. 이 스크립트는 v1 추출용이다. 추가분(v2)은 이전 버전 앱을 뺀 모집단에서 뽑아야 하는데 아직 지원하지 않아, 다른 `test_vN.csv`가 있으면 멈춘다.

관찰용으로는 분할 APK까지 받는다. 기존 `ml/data/apk/`는 base만 있어 436개 중 387개가 설치되지 않는다.

```bash
tail -n +2 ml/label/test_v1.csv | cut -d, -f1 > ml/data/test_v1_ids.csv
script -qF ml/data/apkeep_split.log apkeep -c ml/data/test_v1_ids.csv -d google-play -i ml/data/apkeep.ini --accept-tos \
  -o locale=ko_KR,timezone=Asia/Seoul,split_apk=true -s 3000 -r 1 ml/data/apk_split/
```

## 6. 한계

- 에뮬레이터에서는 광고 네트워크가 광고를 덜 내보낼 수 있다(광고 미노출 → 정상으로 잘못 판정할 위험).
- 10분 대기로는 몇 시간·며칠 뒤에 시작하는 앱 밖 광고를 놓친다.
- 홈 대기 중에는 1분마다 한 번씩만 기록한다. 그 사이에 떴다 사라진 앱 밖 광고는 놓칠 수 있다(에뮬레이터 화면 녹화는 한 번에 최대 180초라 10분 연속 녹화는 하지 않음).
- `monkey`는 사람처럼 쓰지 않는다. 기능 화면 깊숙이 들어가야 뜨는 광고를 놓칠 수 있다.
