-- 오탐 익명 신고 (US7, openapi reportFalsePositive). 서버는 집계만 한다:
-- 기기·사용자를 묶을 수 있는 값(기기 ID, Firebase 사용자, 받은 시각)은 두지 않고, 같은 앱·판·판정기에 몇 번 왔는지와 날짜만 센다
CREATE TABLE false_positive (
    source text NOT NULL CHECK (source IN ('install_scan', 'symptom')),
    package_name text NOT NULL,
    version_code bigint NOT NULL,
    signing_cert_sha256 text NOT NULL,
    -- 판정기 판: install_scan은 모델 판, symptom은 감지 규칙 판
    judge_version text NOT NULL,
    -- install_scan의 근거 코드 (정렬해 쉼표로 이음). symptom은 빈 문자열
    reason_codes text NOT NULL DEFAULT '',
    reports integer NOT NULL,
    first_day date NOT NULL,
    last_day date NOT NULL,
    PRIMARY KEY (source, package_name, version_code, signing_cert_sha256, judge_version, reason_codes)
);
