-- 인증 시스템 강화 - 비밀번호 재설정·이메일 인증용 단발성 토큰
-- ddl-auto: none 이므로 수동 실행 필요

-- purpose: 'PASSWORD_RESET' | 'EMAIL_VERIFY'. member_sessions와 마찬가지로 원문 토큰은
-- 저장하지 않고 sha256 해시만 저장한다(DB가 새도 재사용 불가). consumed_at이 채워지면
-- 재사용 불가 - 이미 쓴 링크를 다시 눌러도 막힌다.
CREATE TABLE IF NOT EXISTS member_action_tokens (
    id          UUID        PRIMARY KEY,
    member_id   UUID        NOT NULL,
    purpose     VARCHAR(20) NOT NULL,
    token_hash  CHAR(64)    NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_member_action_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_member_action_tokens_member FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_member_action_tokens_member_purpose ON member_action_tokens (member_id, purpose);
