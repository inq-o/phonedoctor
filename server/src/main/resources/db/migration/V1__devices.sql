-- 기기 (data-model §1 DEVICE). Firebase 사용자(uid)당 1개, 역할은 바꿀 수 없음
CREATE TABLE device (
    device_id        text PRIMARY KEY,
    firebase_uid     text        NOT NULL UNIQUE,
    role             text        NOT NULL CHECK (role IN ('protected', 'guardian')),
    display_name     text        NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 20),
    fcm_token        text        NOT NULL,
    app_version_code integer     NOT NULL,
    sdk_int          integer     NOT NULL,
    manufacturer     text,
    locale           text,
    notify_caution   boolean     NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL,
    last_seen_at     timestamptz,
    last_checked_at  timestamptz,           -- 상태 보고의 검사 시각, 서버 시각을 넘지 않게 자른 값 (F6 기준)
    checked_reported_at timestamptz,        -- 지금까지 받은 보고의 기기 시각 중 가장 큰 값 (새 보고인지 가르는 데만 씀)
    protection       jsonb
);

-- POST 재전송 대비 (openapi 공통 규칙). 성공 응답만 24시간 보관
CREATE TABLE idempotency_key (
    firebase_uid text        NOT NULL,
    key          text        NOT NULL,
    method       text        NOT NULL,
    path         text        NOT NULL,
    status       integer     NOT NULL,
    body         text        NOT NULL,
    created_at   timestamptz NOT NULL,
    PRIMARY KEY (firebase_uid, key)
);
CREATE INDEX idempotency_key_created_at ON idempotency_key (created_at);
