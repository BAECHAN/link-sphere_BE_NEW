-- 인증 시스템 강화(세션 폐기·회원탈퇴·이메일인증) - members 컬럼 추가
-- ddl-auto: none 이므로 수동 실행 필요
-- 반드시 세션 테이블 도입 BE 코드(Phase 3) 배포 "전"에 실행할 것 - 컬럼이 없으면
-- TableMember 매핑이 깨져 모든 member SELECT(로그인 포함)가 즉시 실패한다.

-- 1. 회원 탈퇴 표시. 탈퇴 시 이 시각을 채우고 이메일·비밀번호·닉네임·이미지를 익명화한다
--    (계정 행 자체는 삭제하지 않는다 - 글·댓글이 이 행을 계속 참조하므로 삭제하면 그
--    글이 섞인 목록 조회가 깨진다. docs/plans/2026-09-28-auth-hardening.md의 F4 참고)
ALTER TABLE members
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ NULL;

-- 2. 이메일 인증 여부. 글쓰기·댓글쓰기 게이트에만 쓴다(로그인·읽기는 막지 않음).
--    DEFAULT TRUE로 추가해 기존 가입자를 전부 그랜드파더링한다 - 가짜 이메일로 가입한
--    계정이 영구히 인증 못 하는 상태로 남지 않게 하기 위함이다. 이 ALTER 자체가 기존
--    행을 TRUE로 채우고, 이후 가입 코드가 매 INSERT마다 FALSE를 명시적으로 써서 앞으로
--    가입하는 사람만 실제로 인증 절차를 거친다.
ALTER TABLE members
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT TRUE;
