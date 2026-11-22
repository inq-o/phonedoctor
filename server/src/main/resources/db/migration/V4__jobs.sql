-- 예약 작업 (Jobs.kt)
-- 연락 끊김(F6) 알림을 보낸 시각. 그 뒤 새 검사 보고가 오면 다음 끊김 때 다시 알림
ALTER TABLE link ADD COLUMN contact_lost_at timestamptz;

CREATE INDEX alert_remind_at ON alert (remind_at) WHERE status = 'open' AND remind_at IS NOT NULL;
CREATE INDEX alert_open_expires_at ON alert (expires_at) WHERE status = 'open';
CREATE INDEX alert_delete_at ON alert (delete_at);
