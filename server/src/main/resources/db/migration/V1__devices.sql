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
    last_checked_at  timestamptz,
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
