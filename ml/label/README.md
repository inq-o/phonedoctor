# 라벨링·테스트셋 (T026)

직접 수집한 앱에 "광고 도배" 정답을 붙이고, 평가용 테스트셋을 버전별로 동결한다. 라벨은 특징(권한·SDK·이름)이 아니라 **에뮬레이터에서 실제로 본 행동**에서만 나온다. 특징으로 라벨을 만들면 모델이 규칙을 외워 평가가 부풀려진다.

## 1. 판정 기준

Play Protect "Disruptive ads" 정의와 Play 정책의 "예상치 못한 전면 광고" 기준을 따른다.

| 코드 | 관찰 기준 | 판정 |
|---|---|---|
| R1 | 앱 밖에서 광고 표시 (홈 화면, 다른 앱 위, 잠금화면) | 광고 도배 |
| R2 | 광고성 알림 발송 | 광고 도배 |
| R3 | 닫기 어려운 광고 (닫기 버튼 숨김·지연, 뒤로가기 막힘) | 광고 도배 |
| R4 | 삭제 방해 (기기 관리자 요구, 아이콘 숨김) | 광고 도배 |
| R5 | 가짜 경고("바이러스 발견", "메모리 90%")로 광고·결제 유도 | 광고 도배 |
| R6 | 예고 없이 뜨거나 건너뛸 수 없는 전면 광고 (앱 실행 직후, 작업 도중, 화면 전환마다) | 광고 도배 |
| — | 자연스러운 멈춤 지점(게임 오버, 단계 클리어, 작업 완료)에서 뜨고 건너뛸 수 있는 전면 광고 | 정상 |
| — | 앱 안에서만 보이는 배너·네이티브 광고 | 정상 |
| — | 설치·실행이 안 되거나 관찰 중 화면을 판단할 수 없음 | 제외 (`excluded`) |

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

- 설치는 `-g`로 런타임 권한을 모두 허용한다(시니어가 권한 창을 대개 허용하는 상황을 가정). 오버레이 같은 특수 권한은 앱이 요청할 때만 생긴다.
- 사용 3분: `monkey` 무작위 입력(0.7초 간격, 시드 고정, 대상 앱 안에서만)을 180초 동안. 화면 녹화 `use.webm`(호스트 쪽 녹화라 캡처 방지 앱도 찍힘).
- 홈 대기 10분: 1분마다 화면·현재 포커스 창·대상 앱 소유 창 기록. 끝에 잠금화면 캡처, 알림·기기 관리자·앱 권한(appops) 덤프.
- 기기 관리자를 해제하고 앱을 지운다.

결과는 `ml/data/observe/<package>/`에 남는다(레포 제외). 판정 근거로 쓰는 파일은 다음과 같다.

| 파일 | 근거 |
|---|---|
| `bg_*.png`, `lock.png`, `focus.log`, `windows.log` | R1 (앱 밖·오버레이·잠금화면) |
| `notifications.txt`, `notifications.png` | R2 |
| `use.webm`, `use_*.png` | R3·R5·R6 |
| `device_admin.txt`, `meta.txt`의 `launcher=` | R4 |

## 3. 라벨 기록

`test_v1_labels.csv` — 패키지마다 한 줄.

| 열 | 값 |
|---|---|
| `package` | 패키지명 |
| `label` | `adware` / `normal` / `excluded` |
| `codes` | 해당 코드 `R1;R3` (정상이면 비움) |
| `evidence` | 근거 파일과 한 줄 설명 (예: `bg_04.png 홈 화면 전면 광고`) |
| `observed` | 관찰 날짜 |

## 4. 테스트셋 규칙

1. **버전으로 쌓는다.** `test_v1`(150개) → `test_v2`(+추가분). 한 번 넣은 앱은 빼거나 바꾸지 않는다. 수치에는 어느 버전 기준인지 함께 적는다.
2. **추가분도 무작위로 뽑는다.** 모델이 틀린 앱을 골라 넣지 않는다. 어려운 사례는 별도 도전셋으로 두고 대표 수치에 섞지 않는다.
3. **임계값은 테스트셋에서 정하지 않는다.** FPR 5% 지점은 검증셋에서 정하고, 테스트셋에서는 측정만 한다.
4. **학습 데이터와 겹치지 않게 한다.** 테스트셋 앱은 학습에 쓰지 않는다. MH-1M에는 패키지명이 없어 SHA-256으로만 대조한다(v1: 436개 중 겹침 0, 2026-10-12). 같은 앱의 다른 버전이 MH-1M에 있는지는 확인할 수 없다.

## 5. 표본

```bash
python ml/label/sample_testset.py ml/collect/packages.csv ml/data/apk ml/label/test_v1.csv
```

받은 APK 436개 중 회색지대 100 + 시니어 50, 시드 20261012. 광고 도배가 회색지대에 몰릴 것으로 보고 회색지대를 더 뽑았다.

관찰용으로는 분할 APK까지 받는다. 기존 `ml/data/apk/`는 base만 있어 436개 중 387개가 설치되지 않는다.

```bash
tail -n +2 ml/label/test_v1.csv | cut -d, -f1 > ml/data/test_v1_ids.csv
script -qF ml/data/apkeep_split.log apkeep -c ml/data/test_v1_ids.csv -d google-play -i ml/data/apkeep.ini --accept-tos \
  -o locale=ko_KR,timezone=Asia/Seoul,split_apk=true -s 3000 -r 1 ml/data/apk_split/
```

## 6. 한계

- 에뮬레이터에서는 광고 네트워크가 광고를 덜 내보낼 수 있다(광고 미노출 → 정상으로 잘못 판정할 위험).
- 10분 대기로는 몇 시간·며칠 뒤에 시작하는 앱 밖 광고를 놓친다.
- `monkey`는 사람처럼 쓰지 않는다. 기능 화면 깊숙이 들어가야 뜨는 광고를 놓칠 수 있다.
