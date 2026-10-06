# 푸시 메시지 계약 (FCM)

- 서버 → 기기 푸시 형식. 서버 API는 [openapi.yaml](openapi.yaml)
- 전송: FCM HTTP v1. **모두 data 메시지**로 보내고 알림 표시는 앱이 직접 함 (문구·채널·딥링크를 앱이 통제)
- 참고: [Firebase — Android 메시지 우선순위](https://firebase.google.com/docs/cloud-messaging/android/message-priority), [Firebase 블로그 — FCM 알림이 Android 사용자에게 도달하게 하기(2025-04)](https://firebase.blog/posts/2025/04/fcm-on-android/)

## 1. 공통 규칙

| 항목 | 규칙 | 이유 |
|---|---|---|
| 우선순위 | 사람이 바로 봐야 하는 것만 `high` | high인데 알림을 안 띄우면 FCM이 우선순위를 낮춤 |
| `onMessageReceived` | 받은 즉시 알림 표시, 네트워크 호출 금지. 상세는 알림 탭 후 화면에서 `GET /alerts/{id}` | Firebase 권고 |
| 누락 대비 | 앱 실행·홈 진입 시 `GET /alerts?updatedSince=` 로 당겨오기 | 제조사 배터리 최적화로 FCM 누락 가능 |
| 개인정보 | 페이로드에 앱 아이콘·근거 상세를 넣지 않음. ID와 짧은 표시 문구만 | 잠금화면 노출 최소화 |
| TTL | 승인 요청 48시간, 나머지 24시간 | 무응답 처리(FR-026)와 맞춤 |
| 버전 | 모든 메시지에 `v: "1"` | 형식 변경 대비 |

## 2. 메시지 종류

| type | 받는 쪽 | 우선순위 | 알림 채널 | 탭하면 |
|---|---|---|---|---|
| `LINK_REQUESTED` | 시니어 | high | `link` | 연결 확인 화면 |
| `LINK_ACTIVATED` | 보호자 | high | `link` | 보호자 홈 |
| `LINK_REVOKED` | 상대 기기 | normal | `link` | 연결 목록 |
| `ALERT_CREATED` | 보호자 전원 | high | `alert` | 요청 상세 |
| `ALERT_REMINDER` | 미응답 보호자 | high | `alert` | 요청 상세 |
| `ALERT_UPDATED` | 다른 보호자 | normal | (알림 없음, 목록 갱신) | — |
| `DECISION_MADE` | 시니어 | high | `alert` | 시니어 앱 상세 (삭제 유도) |
| `ALERT_RESOLVED` | 보호자 전원 | normal | `result` | 요청 상세 |
| `MODEL_UPDATED` | 시니어 | normal | (알림 없음) | WorkManager로 모델 받기 |

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
      "protectedName": "엄마",
      "appLabel": "초고속 클리너",
      "level": "RISK"
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
| `result` | 처리 결과 | LOW | |
| `status` | 보호 중 표시 | MIN | 상시 표시 (스토커웨어 정책: 모니터링 사실 고지). 포그라운드 서비스 아님 |

- `USE_FULL_SCREEN_INTENT`는 쓰지 않음 — Android 14부터 통화·알람 앱에만 허용
- 시니어 기기에서 알림 권한(`POST_NOTIFICATIONS`, Android 13+)이 꺼져 있으면 홈 화면 상단에 "알림이 꺼져 있어요" 카드 고정
