-- 인증 시스템 강화 - 로그인 실패·가입·비밀번호재설정요청 등 횟수 제한용 카운터
-- ddl-auto: none 이므로 수동 실행 필요

-- 고정 윈도(fixed window) 카운터. bucket_key 예: "login-fail:<sha256(email)>",
-- "signup:ip:<ip>". window_start는 그 윈도의 시작 시각(예: 15분 단위로 내림한 시각).
-- 이메일은 해시로만 키에 들어가 이 테이블 자체엔 PII가 남지 않는다.
CREATE TABLE IF NOT EXISTS auth_rate_limits (
    bucket_key   VARCHAR(200) NOT NULL,
    window_start TIMESTAMPTZ  NOT NULL,
    hit_count    INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_auth_rate_limits PRIMARY KEY (bucket_key, window_start)
);

CREATE INDEX IF NOT EXISTS idx_auth_rate_limits_window ON auth_rate_limits (window_start);
