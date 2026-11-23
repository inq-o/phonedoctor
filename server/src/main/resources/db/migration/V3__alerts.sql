-- 승인 요청 (data-model §1 ALERT·DECISION·OUTCOME). 결정·결과는 요청당 1개라 같은 행에 둔다
CREATE TABLE alert (
    alert_id            text        PRIMARY KEY,
    protected_device_id text        NOT NULL REFERENCES device (device_id) ON DELETE CASCADE,
    status              text        NOT NULL CHECK (status IN ('open', 'decided', 'resolved', 'expired')),
    source              text        NOT NULL CHECK (source IN ('install_scan', 'symptom')),
    kind                text        NOT NULL CHECK (kind IN ('approval', 'notice')),
    package_name        text        NOT NULL,
    signing_cert_sha256 text        NOT NULL,
    version_code        bigint      NOT NULL,
    app                 jsonb       NOT NULL,
    verdict             jsonb,
    symptom             jsonb,
    detected_at         timestamptz NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    remind_at           timestamptz,           -- 보호자 재알림 예정 (approval만, 생성 + 24시간)
    expires_at          timestamptz NOT NULL,  -- open이면 이 시각에 expired (생성 + 7일)
    delete_at           timestamptz NOT NULL,  -- 생성 + 90일 삭제 (NFR-09)
    decision            text        CHECK (decision IN ('recommend_uninstall', 'allow')),
    message_to_senior   text,
    decided_by_id       text,                  -- 보호자 기기가 지워져도 기록은 남게 외래 키 없이 이름과 함께 둔다
    decided_by_name     text,
    decided_at          timestamptz,
    outcome             text        CHECK (outcome IN ('uninstalled', 'declined', 'already_removed', 'suspended', 'allowed')),
    outcome_at          timestamptz
);
-- 같은 앱(패키지·서명·버전)의 열린 요청은 시니어 기기당 1개
CREATE UNIQUE INDEX alert_open_app ON alert (protected_device_id, package_name, signing_cert_sha256, version_code) WHERE status = 'open';
CREATE INDEX alert_device_updated ON alert (protected_device_id, updated_at, alert_id);

-- 요청을 받은 연결. 연결을 끊으면 이 행이 지워지고, 받은 연결이 하나도 안 남은 요청은 삭제 (NFR-09)
CREATE TABLE alert_link (
    alert_id text NOT NULL REFERENCES alert (alert_id) ON DELETE CASCADE,
    link_id  text NOT NULL REFERENCES link (link_id) ON DELETE CASCADE,
    PRIMARY KEY (alert_id, link_id)
);
CREATE INDEX alert_link_link ON alert_link (link_id);
