-- 시니어 기본 이름 "부모님"을 없앤다 (ux-flows §2 상대 폰 이름: 역할을 문구에 박지 않음).
-- 이름을 정하지 않은 시니어는 빈 값, 보호자 화면은 보호자가 고른 표시 이름이나 "연결한 폰"으로 보인다
ALTER TABLE device DROP CONSTRAINT device_display_name_check;
ALTER TABLE device ADD CONSTRAINT device_display_name_check CHECK (char_length(display_name) BETWEEN 0 AND 20);
UPDATE device SET display_name = '' WHERE role = 'protected' AND display_name = '부모님';
