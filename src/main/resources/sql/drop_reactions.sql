-- 좋아요(reactions) 폴리모픽 구조 제거 - 구 테이블 정리 (Phase C, 파괴적)
-- ddl-auto: none 이므로 수동 실행 필요
--
-- ⚠️ 이 파일은 배포가 완료되고 아래가 전부 검증된 뒤에만 실행한다:
--   1. create_post_comment_reactions.sql 의 백필이 끝나고 검증 SELECT 가 기대 등식을 만족
--      (new_post = old_post - orphan_post, new_comment = old_comment - orphan_comment - tombstone_comment)
--   2. BE 배포 완료 (prod alias 이동 완료), 좋아요·게시글 목록·댓글 목록 정상 동작 확인
--   3. 컷오버 창 직후 백필 재실행(create_post_comment_reactions.sql 3번 블록)까지 완료
--   4. 롤백 창 종료 — prod alias 를 구버전으로 되돌릴 계획이 없는 상태
--
-- 되돌릴 수 없다 — reactions 는 이 시점부터 어떤 코드도 참조하지 않으므로
-- 롤백이 필요하면 이 파일을 실행하기 전 상태로 돌아가야 한다.

DROP TABLE IF EXISTS reactions;
