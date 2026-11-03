# 증상 감지 특징 추출기 (실험용)

사용 기록(`UsageEvents`)에서 앱 화면 전환마다 특징 F0~F10을 뽑고, 규칙 v0를 특징으로 다시 쓴 `rule_v0t`로 판정한다. 기기 추출기(Kotlin)가 같은 입력에서 같은 행을 내는지 대조하는 **PC 기준 구현**이다.

- 상태: 실험용 초안(`fx0-draft`). 실기기 검증 전이고 제품 코드가 아니다.
- 표준 라이브러리만 쓴다(Python 3). 원본 규칙 대조에 상위 폴더의 `rule_v0.py`를 읽는다.

## 실행

```bash
python3 testapps/poc/features/featx.py          # 검증 사례 52개
python3 testapps/poc/features/mutate.py         # 변형 시험 (코드를 한 곳씩 망가뜨려 사례가 잡는지)
python3 testapps/poc/features/make_cases.py testapps/poc/features/cases.json   # 사례 다시 만들기
python3 testapps/poc/features/featx.py real ml/data/poc/launchers.csv $(find ml/data/poc -name '*events.csv')
```

- 기대값은 추출기를 돌리기 전에 손으로 계산해 `make_cases.py`에 적는다. `cases.json`은 그 출력이다.
- `real`은 레포에서 제외한 실측 자료(`ml/data/`)가 있어야 한다.

## 입력과 출력

| 구분 | 내용 |
|---|---|
| 조회 범위 | `query_range(state, wall, elapsed, boot_count)` → `[begin, end)`. 시계가 뒤로 가면 옛 커서가 `wall`보다 클 수 있어 다시 계산한다 |
| 입력 | 조회 1회분 `{wall_to, elapsed, boot_count, events}`. `events`는 OS가 돌려준 순서 그대로, 조회가 null이면 `None` |
| 상태 | 커서(워터마크), 관측 구간, 같은 ms 순서별 "세계", 대기 중인 부팅 경계, 버릴 범위(끝을 모르면 열린 격리). JSON으로 저장·복구한다 |
| 출력 | RESUMED마다 한 행: `segment_id`, `query_id`, `source_seq`, `ts`, `pkg`, `cls`, 특징(값, 상태), `meta_validity`, `rule_v0t`(`outcome`, `failed`, `unknown`), `order_basis`, `recover_basis` |
| 개수 | 상태의 `counts`. 조회에서 만난 횟수라 같은 기록을 다시 조회하면 다시 센다(`pre_cursor_ignored` = 커서 앞이라 건너뛴 횟수, `uncertain_discarded` = 버릴 범위라 건너뛴 횟수). 고유 이벤트 수가 아니므로 누락 수나 평가 분모로 쓰지 않는다 |

특징 상태는 `OBSERVED`, `NONE_IN_WINDOW`(창 안에 없음이 확실), `NOT_COVERED`(관측 전이라 모름), `LOOKUP_FAILED`, `TIED`(같은 ms 순서에 따라 값이 갈림)이다. `rule_v0t`는 참·거짓·모름 3값으로 판정하고, 모름이 남거나 순서에 따라 결과가 갈리면 `HOLD`를 낸다.

## 검사기가 확인하는 것

| 사례 형식 | 자동 확인 |
|---|---|
| `events` (한 번 조회) | 모든 묶음 경계에서 두 조회로 나누기, 조회마다 저장 실패 뒤 재실행, 같은 ms 묶음의 조회 순서 바꾸기(뒤집기·한 칸 돌리기) |
| `queries` (조회를 직접 적음) | 조회마다 저장 실패 뒤 재실행, 같은 ms 순서 바꾸기, `range`가 있으면 `query_range` 결과 |

`mutate.py`의 변형 43개 중 42개가 잡힌다. 남은 하나("오래된 상태 정리 끔")는 결과가 같아야 맞는 변형이다. 그 밖의 변형이 안 잡히거나, 적용되지 않거나, 컴파일되지 않거나 사례 요약 전에 멈추면 exit 1이다(멈춘 실행은 검출로 세지 않는다). 사례·사례 재생성 일치·변형 시험은 CI(`Python and shell checks`)에서도 돈다.

## 가정과 미확인 사항

| 항목 | 지금 처리 | 근거·상태 |
|---|---|---|
| 기록 지연 | 조회 끝 5초 전(`SETTLE_MS`)까지만 확정 | **미확인.** 공식 문서에 상한이 없다. 넘으면 늦게 기록된 이벤트를 반영하지 못한다(사례 4c) |
| 보존 기간 | 3일 넘게 조회하지 못하면 공백 구간 | **미확인.** 공식 문서는 "a few days" |
| 같은 ms 순서 | 기본: 가능한 순서를 모두 계산. 똑같은 이벤트끼리만 순서를 고정(바꿔도 결과가 같음) | 공식 문서에서 같은 시각 이벤트의 순서 보장을 찾지 못했다. 조회 순서는 근거로 쓰지 않는다 |
| 생명주기 가정 (`order_assume="lifecycle"`) | 액티비티마다 RESUMED·PAUSED가 번갈아 오고(이전 상태는 관측값), 다른 액티비티끼리는 PAUSED가 앞 | **분석용, 확정 판정에 안 씀.** 콜백 순서(앞 액티비티 onPause → 다음 onResume)가 사용 기록의 같은 ms 순서에도 지켜지는지 미확인. 같은 클래스 여러 인스턴스·다중 창에서 깨질 수 있다. 행에 `order_basis`로 표시 |
| OS의 기록 시각 보정 | 옮기는 경우와 옮기지 않는 경우를 모두 가정 | AOSP 15 원문(`android-15.0.0_r1`, `frameworks/base/services/usage/`): 같은 부팅에서 시계가 2초 넘게 어긋나면 저장된 기록 파일의 기준 시각을 그 차이만큼 옮긴다(`UserUsageStatsService.checkAndGetTimeLocked`, `UsageStatsDatabase.onTimeChanged`). 부팅할 때는 지금 시각보다 늦게 시작한 기록 파일을 지우고, 옛 부팅 일별 기록의 `max(lastTimeSaved, endTime)`으로 SHUTDOWN을, 지금 시각으로 STARTUP을 기록한다. 이 SHUTDOWN에 부팅을 가르는 식별자는 없다(`UsageStatsDatabase.init`, `UserUsageStatsService.init`). **갤럭시 펌웨어에서 같은지 미확인** |
| 시계 변경 | 벽시계 경과와 elapsed 경과가 1초 넘게 어긋나면 그 조회분을 버리고 새 시계 기준으로 커서를 다시 잡음 | 허용 오차를 OS 기준(2초)보다 작게 둬, OS가 기록을 옮겼는데 앱은 못 알아채는 경우를 줄인다(사례 8d). 버릴 범위의 끝은 두 경우 중 늦은 쪽이다: 옮기지 않으면 옛 시계 기록이 있을 수 있는 `앞 조회 시각 + elapsed 경과`, 옮기면 이미 처리한 기록이 앞으로 옮겨져 다시 보일 수 있는 `앞 커서 + 전진 폭 + 4초`. 버릴 범위가 겹치면 합치고 줄이지 않는다(사례 8c). **4초는 조건부 가정이다.** 앱이 잰 차이(`Δwall − Δelapsed`)와 OS가 옮긴 양은 두 기준점에서 각각 OS 기준(2초)까지 어긋날 수 있다고 보고 더한 값이다. 조회 때 잰 `wall`·`elapsed`와 OS가 보정한 시점의 차이, 측정 오차, OEM 동작은 넣지 않았다. 제품의 안전 상한으로 쓰지 않는다. 갤럭시에서 조회 전후 `wall`·`elapsed`를 기록하고, 다시 조회한 같은 기록이 얼마나 옮겨졌는지 대조해 확인한다 |
| 재부팅 | `BOOT_COUNT` 변화, elapsed 감소, 앞 조회 뒤에 기록된 STARTUP 중 하나 | `BOOT_COUNT`를 일반 앱이 읽을 수 있는지 **미확인.** STARTUP은 API 29부터다 |
| 재부팅 + 시계 후퇴 | 부팅 시작(`wall − elapsed`)이 앞 조회보다 이르면 끝을 모르는 격리를 연다. 새 부팅 시작부터 읽되 행을 만들지 않고 `uncertain_discarded`만 센다. **기본은 닫지 않는다**(사례 16·16a·16g) | 옛 부팅이 앞 조회 뒤 꺼질 때까지 남긴 기록의 끝은 지금 시각만으로 알 수 없다. 옛 부팅의 SHUTDOWN이 보여도 그것이 옛 부팅의 끝인지, 옛 부팅 안의 런타임 재시작인지 가를 수 없다(사례 16g). 재부팅과 시계 전진이 겹치면 꺼져 있던 시간으로 보고 구분하지 못한다 |
| SHUTDOWN 해제 (`recover="shutdown"`) | 열린 격리를 옛 부팅의 SHUTDOWN에서 닫고 새 구간을 연다. SHUTDOWN은 옛 부팅에서 본 마지막 기록 이후 것만 쓰고, OS가 기록을 앞으로 옮겼으면 그만큼 기준도 올린다. 새 구간은 커서보다 앞에서 시작하지 않는다(격리 중 버린 기록을 상태에 넣지 않았으므로). 닫을 수 있는 동안은 커서 앞에 늦게 보이는 SHUTDOWN도 찾도록 기준 시각부터 다시 읽는다. 행에 `recover_basis=shutdown_assumed`로 표시 (사례 16b·16c·16d·16e·16f·16h·16i) | **분석용, 확정 판정에 안 씀.** 옛 부팅이 앞 조회 뒤 런타임 재시작을 했다면 그 SHUTDOWN에서 닫아 옛 기록을 판정한다(사례 16g-S, **한계**). 격리 중 다시 재부팅하거나 런타임 재시작이 보이면 닫지 않는다(사례 16d). 조회마다 과거를 다시 읽어 처리량이 조회 횟수에 대해 제곱으로 늘 수 있고 실기기 비용은 측정하지 않았다 |
| 런타임 재시작 | elapsed가 이어지는 STARTUP은 새 구간을 열되 "없음"을 확정하지 않음 | 공식 문서상 STARTUP은 런타임 재시작에도 기록될 수 있다. 재시작과 시계 변경이 겹치면 재부팅으로 잘못 볼 수 있다 |
| null 조회 | 커서와 시각 기준을 그대로 둠 | 공식 문서는 Android R부터 사용자 잠금 해제 전 조회가 null이라고 하지만, AOSP 15 구현(`UsageStatsManager.queryEvents`)은 서비스가 null을 주면 빈 결과를 돌려준다. 그래서 수집기는 조회 직전 `isUserUnlocked`를 `queries.csv`에 남기고, 잠금 상태 조회는 null과 같이 다룬다(빈 결과를 "없음"으로 읽지 않음). 갤럭시에서 미확인 |

실측 645건(에뮬레이터 덤프 18개)에서 기본 판정은 NORMAL 490, AD 124, HOLD 31이다. 생명주기 가정을 켜면 516, 125, 4가 된다. 차이 27건은 모두 HOLD 쪽으로만 바뀌었고, AD와 NORMAL이 서로 뒤바뀐 경우는 없다. 정확도 평가가 아니라 판정 분포 비교다.

## 아직 하지 않은 것

- 스키마 변경 복구, Room 트랜잭션, Kotlin 구현과의 대조
- 닫지 못한 격리에서 판정을 다시 시작하는 기준. 지금은 계속 행을 만들지 않고 `uncertain_discarded`만 센다. 상태를 조용히 새로 시작해 풀지 않는다. 제품에 옮기기 전에 정하고, 격리 기간을 평가 보고에 드러낸다
- SHUTDOWN 해제를 실기기에 쓰려면: 다시 읽는 조회 횟수·범위의 상한(상한에 닿아도 판정을 다시 시작하지 않음)과 조회 횟수·반환 건수·소요 시간 측정
- 수집기(`testapps/usageprobe`)는 조회마다 전후 `wall`·`elapsed`와 앱이 읽은 `BOOT_COUNT`(못 읽으면 예외 이름)를 `queries.csv`에, 설치·업데이트 시각을 `launchers.csv`에 남긴다. 이 값을 `query_range`·`init_state` 입력으로 옮기는 변환기는 실측 자료를 받은 뒤 만든다
- 실기기 확인 (갤럭시 연결 후 이 순서로)
  1. 수집과 기록 지연: 같은 과거 범위를 시간차를 두고 다시 조회해 늦게 나타나는 이벤트 측정
  2. 재부팅 복구: `BOOT_COUNT`·STARTUP·elapsed가 실제로 어떻게 바뀌는지, 잠금 해제 전 null 조회
  3. 시계 변경 때 저장된 기록의 시각이 옮겨지는지(AOSP 15는 2초 넘게 어긋나면 옮김), 재부팅 때 SHUTDOWN 시각이 옛 부팅의 마지막 기록 이후인지
  4. 같은 ms 묶음의 순서가 생명주기 가정과 맞는지
