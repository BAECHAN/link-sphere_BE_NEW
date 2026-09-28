-- 인증 시스템 강화 - 로그인 세션 테이블 (JWT 대체, access+refresh 통합 관리)
-- ddl-auto: none 이므로 수동 실행 필요
-- 반드시 BE Phase 3(세션 테이블 도입) 코드 배포 "전"에 실행할 것

-- 한 로그인 = 한 행. access/refresh 둘 다 서버가 관리하는 불투명 토큰으로 통일했다
-- (docs/plans/2026-09-28-auth-hardening.md 참고 - JWT는 매 요청마다 서버 상태를 확인해야
-- 하는 순간 이미 이점을 잃는다는 판단). 원문 토큰은 저장하지 않고 sha256 해시만 저장한다.
--
-- access_token_hash: 매 요청마다 조회(SessionAuthenticationFilter가 JwtAuthenticationFilter를
--   대체한다)
-- refresh_token_hash: POST /auth/refresh 때만 조회, 성공 시 새 행으로 회전한다
-- family_id: 회전 계열 식별자. 이미 소비된(consumed) refresh가 다시 쓰이면(탈취 후 재사용
--   의심) 같은 family_id를 가진 행을 전부 폐기한다
-- revoked_at: 로그아웃/전체로그아웃/탈퇴/비밀번호변경 시 채워진다. NULL이면 유효한 세션
CREATE TABLE IF NOT EXISTS member_sessions (
    id                  UUID        PRIMARY KEY,
    member_id           UUID        NOT NULL,
    access_token_hash   CHAR(64)    NOT NULL,
    refresh_token_hash  CHAR(64)    NOT NULL,
    access_expires_at   TIMESTAMPTZ NOT NULL,
    refresh_expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ NULL,
    family_id           UUID        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_member_sessions_access UNIQUE (access_token_hash),
    CONSTRAINT uk_member_sessions_refresh UNIQUE (refresh_token_hash),
    -- 새로 만드는 테이블이라 레거시 고아 행이 없다 - 이 레포의 "members에 FK 안 건다"
    -- 관례(다른 테이블들은 과거 데이터 때문에 못 건다)와 달리 여기는 걸 수 있다.
    -- 실제로는 탈퇴가 하드삭제가 아니라 익명화라 이 CASCADE가 발동할 일은 없지만,
    -- 참조 무결성 방어 차원에서 걸어둔다.
    CONSTRAINT fk_member_sessions_member FOREIGN KEY (member_id) REFERENCES members (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_member_sessions_member ON member_sessions (member_id);
CREATE INDEX IF NOT EXISTS idx_member_sessions_family ON member_sessions (family_id);
