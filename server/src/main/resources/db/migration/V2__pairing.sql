-- 연결 코드 (data-model §1 PAIRING_CODE). 시니어 기기가 발급, 10분 유효, 기기당 유효 코드 1개
CREATE TABLE pairing_code (
    code                text        PRIMARY KEY CHECK (code ~ '^[0-9]{6}$'),
    protected_device_id text        NOT NULL REFERENCES device (device_id) ON DELETE CASCADE,
    expires_at          timestamptz NOT NULL,
    used                boolean     NOT NULL DEFAULT false
);
CREATE INDEX pairing_code_device ON pairing_code (protected_device_id);
CREATE INDEX pairing_code_expires_at ON pairing_code (expires_at);

-- 연결 (data-model §1 LINK). 해제해도 행은 revoked로 남김
CREATE TABLE link (
    link_id             text        PRIMARY KEY,
    protected_device_id text        NOT NULL REFERENCES device (device_id) ON DELETE CASCADE,
    guardian_device_id  text        NOT NULL REFERENCES device (device_id) ON DELETE CASCADE,
    status              text        NOT NULL CHECK (status IN ('pending', 'active', 'revoked')),
    created_at          timestamptz NOT NULL,
    activated_at        timestamptz,
    revoked_at          timestamptz
);
-- 같은 (시니어, 보호자) 쌍의 해제되지 않은 연결은 1개
CREATE UNIQUE INDEX link_pair_open ON link (protected_device_id, guardian_device_id) WHERE status <> 'revoked';
CREATE INDEX link_guardian ON link (guardian_device_id);

-- 연결 코드 입력 시도 (대입 공격 차단, NFR-08 기기당 분당 5회). 지난 1분 것만 의미 있음
CREATE TABLE pairing_attempt (
    firebase_uid text        NOT NULL,
    attempted_at timestamptz NOT NULL
);
CREATE INDEX pairing_attempt_uid ON pairing_attempt (firebase_uid, attempted_at);
