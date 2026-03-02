-- FCM 기기 토큰 저장 테이블
-- 한 유저가 여러 기기(브라우저/Android/iOS)에서 접속할 수 있으므로 별도 테이블로 관리
CREATE TABLE fcm_tokens (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    token       TEXT        NOT NULL UNIQUE,
    platform    VARCHAR(10) NOT NULL DEFAULT 'WEB',  -- WEB, ANDROID, IOS
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_fcm_tokens_user_id ON fcm_tokens(user_id);
