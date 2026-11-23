-- 저장한 응답이 담은 요청. 요청이 지워지면 저장 응답도 지워 재전송으로 다시 나가지 않게 (NFR-09)
ALTER TABLE idempotency_key ADD COLUMN alert_id text REFERENCES alert (alert_id) ON DELETE CASCADE;
CREATE INDEX idempotency_key_alert ON idempotency_key (alert_id) WHERE alert_id IS NOT NULL;
