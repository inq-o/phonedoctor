# 푸시 메시지 계약 (FCM)

- 서버 → 기기 푸시 형식. 서버 API는 [openapi.yaml](openapi.yaml)
- 전송: FCM HTTP v1. **모두 data 메시지**로 보내고 알림 표시는 앱이 직접 함 (문구·채널·딥링크를 앱이 통제)
- 참고: [Firebase — Android 메시지 우선순위](https://firebase.google.com/docs/cloud-messaging/android/message-priority), [Firebase 블로그 — FCM 알림이 Android 사용자에게 도달하게 하기(2025-04)](https://firebase.blog/posts/2025/04/fcm-on-android/)

## 1. 공통 규칙

| 항목 | 규칙 | 이유 |
|---|---|---|
| 우선순위 | 사람이 바로 봐야 하는 것만 `high` | high인데 알림을 안 띄우면 FCM이 우선순위를 낮춤 |
| `onMessageReceived` | 받은 즉시 알림 표시(§2에서 채널이 "알림 없음"인 `ALERT_UPDATED`·`MODEL_UPDATED`는 표시하지 않고 목록 갱신·작업 예약만), 네트워크 호출 금지. 상세는 알림 탭 후 화면에서 `GET /alerts/{id}` | Firebase 권고 |
| 누락 대비 | 앱 실행·홈 진입 시 `GET /alerts?updatedSince=`(요청)와 `GET /links`(연결: 놓친 `LINK_REQUESTED`·`LINK_ACTIVATED`·`LINK_REVOKED`의 상태)로 당겨오기 | 제조사 배터리 최적화로 FCM 누락 가능 |
| 개인정보 | 페이로드에 앱 아이콘·근거 상세를 넣지 않음. ID와 짧은 표시 문구만 | 잠금화면 노출 최소화 |
| TTL | 승인 요청(`ALERT_CREATED`·`ALERT_REMINDER`)과 응답(`DECISION_MADE`) 48시간, 나머지 24시간 | 무응답 처리(FR-026)와 맞춤 |
| 버전 | 모든 메시지에 `v: "1"` | 형식 변경 대비 |

## 2. 메시지 종류

| type | 받는 쪽 | 우선순위 | 알림 채널 | 탭하면 |
|---|---|---|---|---|
| `LINK_REQUESTED` | 시니어 | high | `link` | 연결 확인 화면 |
| `LINK_ACTIVATED` | 보호자 | high | `link` | 보호자 홈 |
| `LINK_REVOKED` | 상대 기기 | normal | `link` | 연결 목록 |
| `ALERT_CREATED` | 보호자 전원 | high | `alert` | 요청 상세 |
| `ALERT_REMINDER` | 미응답 보호자 | high | `alert` | 요청 상세 |
| `ALERT_NOTICE` | 보호자 전원 | normal | `notice` | 요청 상세 (승인 버튼 없음) |
| `ALERT_UPDATED` | 다른 보호자(먼저 온 결정 반영), 보호자 전원(열린 요청에 근거 추가·갱신) | normal | (알림 없음, 목록·상세 갱신) | — |
| `DECISION_MADE` | 시니어 | high | `alert` | 시니어 앱 상세 (삭제 유도) |
| `ALERT_RESOLVED` | 보호자 전원 | normal | `result` | 요청 상세 |
| `CONTACT_LOST` | 보호자 | normal | `link` | 보호자 홈 (연락 끊김 카드, ux-flows F6) |
| `MODEL_UPDATED` | 시니어 | normal | (알림 없음) | WorkManager로 모델 받기 |

- `ALERT_NOTICE`: 보호자가 허용한 앱이 증상 기준을 넘었을 때(openapi `kind: notice`, FR-007). 승인 요청이 아니라서 `normal`·별도 채널 `notice`
- `ALERT_CREATED`·`ALERT_NOTICE`·`ALERT_REMINDER`·`ALERT_RESOLVED`는 받는 보호자의 `linkId`를 담음 (2026-11-25). 보호자 앱은 이 값으로 보호자 폰에만 저장한 표시 이름(ux-flows §2)을 찾아 알림 제목을 만든다. 표시 이름이 없으면 `protectedName`
- `ALERT_CREATED`·`ALERT_NOTICE`·`ALERT_REMINDER`는 `source`(`install_scan` / `symptom`)를 담고, `symptom` 근거가 있으면 `symptomCount`(= openapi `symptom.count`를 문자열로)와 `symptomDate`(= `symptom.date`, 시니어 기기 현지 날짜 `YYYY-MM-DD`)도 담음 (FCM data 값은 모두 문자열) — 알림 문구를 고르는 용도 (ux-flows §6). `symptomDate`가 받은 날의 오늘이 아니면 "오늘" 대신 날짜를 씀(자정 넘어 도착 대비). 시각·근거 상세는 넣지 않음

- 연결 메시지(`LINK_*`)는 `linkId`와 보낸 쪽 표시 이름을 담음. 키 이름은 보낸 쪽 역할: 보호자가 보내면 `guardianName`(`LINK_REQUESTED`, 보호자가 끊은 `LINK_REVOKED`), 시니어가 보내면 `protectedName`(`LINK_ACTIVATED`, 시니어가 끊은 `LINK_REVOKED`). 기기 삭제로 끊긴 연결도 `LINK_REVOKED`
- `ALERT_REMINDER`는 `ALERT_CREATED`와 같은 값(24시간 무응답 1번). `CONTACT_LOST`는 `linkId`·`protectedName` — 시니어 기기의 검사 보고가 48시간 없을 때 끊김 한 번에 1번 (새 보고가 온 뒤 다시 끊기면 또)
- `ALERT_UPDATED`는 `alertId`만. `ALERT_RESOLVED`는 `alertId`·`outcome`·`protectedName`·`appLabel`. `DECISION_MADE`는 아래 예시와 같음(`messageToSenior`는 넣지 않음 — 잠금화면 노출 최소화, 상세 화면에서 `GET /alerts/{id}`)
- 서버는 DB 반영이 끝난 뒤 응답 전에 보냄. 전송이 실패해도 요청은 성공 (위 "누락 대비"로 당겨 옴)
- 같은 일로 두 번 보내지 않되, 보낸다는 보장도 없음(최대 1번): 커밋 직후 서버가 죽거나 FCM이 실패하면 그 푸시는 다시 가지 않는다. 재알림(`ALERT_REMINDER`)·연락 끊김(`CONTACT_LOST`)도 같음. 앱은 목록 당겨오기로 상태를 맞춘다

## 3. 페이로드 예시

```json
{
  "message": {
    "token": "<guardian fcm token>",
    "android": { "priority": "high", "ttl": "172800s" },
    "data": {
      "v": "1",
      "type": "ALERT_CREATED",
      "alertId": "al_7Gx2",
      "linkId": "6f1c…",
      "protectedName": "김영희",
      "appLabel": "초고속 클리너",
      "source": "symptom",
      "symptomCount": "12",
      "symptomDate": "2026-10-23"
    }
  }
}
```

```json
{
  "message": {
    "token": "<protected fcm token>",
    "android": { "priority": "high", "ttl": "86400s" },
    "data": {
      "v": "1",
      "type": "LINK_REQUESTED",
      "linkId": "6f1c…",
      "guardianName": "민지"
    }
  }
}
```

```json
{
  "message": {
    "token": "<protected fcm token>",
    "android": { "priority": "high", "ttl": "172800s" },
    "data": {
      "v": "1",
      "type": "DECISION_MADE",
      "alertId": "al_7Gx2",
      "decision": "recommend_uninstall",
      "guardianName": "딸",
      "packageName": "com.example.cleaner"
    }
  }
}
```

## 4. 알림 채널 (앱에서 생성)

| 채널 ID | 이름(사용자에게 보임) | 중요도 | 비고 |
|---|---|---|---|
| `alert` | 위험한 앱 알림 | HIGH | 소리·진동. 시니어 기기에서는 끄기 전에 경고 |
| `link` | 가족 연결 | DEFAULT | |
| `notice` | 허용한 앱 소식 | DEFAULT | 보호자 기기만. `ALERT_NOTICE` (승인 요청 아님) |
| `result` | 처리 결과 | LOW | |
| `status` | 보호 중 표시 | MIN | 상시 표시 (스토커웨어 정책: 모니터링 사실 고지). 포그라운드 서비스 아님 |

- `USE_FULL_SCREEN_INTENT`는 쓰지 않음 — Android 14부터 통화·알람 앱에만 허용
- 시니어 기기에서 알림 권한(`POST_NOTIFICATIONS`, Android 13+)이 꺼져 있으면 홈 화면 상단에 "알림이 꺼져 있어요" 카드 고정
