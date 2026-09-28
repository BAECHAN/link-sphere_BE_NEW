-- FCM 푸시를 로그인 세션 생명주기에 바인딩 - fcm_tokens 컬럼 추가
-- ddl-auto: none 이므로 수동 실행 필요
-- 반드시 이 BE 코드 배포 "전"에 실행할 것 - 컬럼이 없으면 FCM 토큰 등록·발송 쿼리가
-- 즉시 SQL 오류로 실패한다(ddl-auto: none이라 애플리케이션 기동 자체는 됨).

-- 이 토큰을 등록한 세션의 회전 계열(member_sessions.family_id)을 기록한다. 댓글 발송
-- 시점(FcmService.sendToUser)마다 그 계열이 아직 살아있는지(revoked_at IS NULL AND
-- refresh_expires_at > now) 확인해, 죽은 계열에 묶인 토큰은 지우고 발송 대상에서
-- 제외한다 - 로그인 세션이 자연 만료돼도 그 기기로 계속 푸시가 가던 문제를 막는다.
-- NULL이면(이 컬럼 도입 이전 레거시 행) 즉시 비활성으로 간주된다 - 재로그인하면
-- FE가 새 family로 재등록한다.
ALTER TABLE fcm_tokens
    ADD COLUMN IF NOT EXISTS session_family_id UUID NULL;

CREATE INDEX IF NOT EXISTS idx_fcm_tokens_session_family ON fcm_tokens (session_family_id);
