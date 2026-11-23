-- 예약 작업 (Jobs.kt)
-- 연락 끊김(F6) 알림을 보낸 기준 시각 = max(마지막 검사 보고, 연결 확정). 기준이 이보다 뒤로 바뀐 뒤 다시 끊기면 또 알림.
-- (보낸 시각이 아니라 기준을 남기는 이유: 알림 뒤에 늦게 도착한 옛 보고가 다음 알림을 영영 막지 않게)
ALTER TABLE link ADD COLUMN contact_lost_baseline timestamptz;

CREATE INDEX alert_remind_at ON alert (remind_at) WHERE status = 'open' AND remind_at IS NOT NULL;
CREATE INDEX alert_open_expires_at ON alert (expires_at) WHERE status = 'open';
CREATE INDEX alert_delete_at ON alert (delete_at);
