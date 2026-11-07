-- 비교 실험용 더미 데이터. 실제 사용자·운영 값이 아니다.
-- 비밀번호는 누구나 알아볼 수 있는 가짜 값이고, 해시는 파일에 적지 않고 실행할 때 만든다.
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;

INSERT INTO public.members (id, email, nickname, password, role, email_verified)
VALUES (
    '00000000-0000-4000-8000-000000000001',
    'experiment-user@example.invalid',
    '실험회원',
    extensions.crypt('experiment-password', extensions.gen_salt('bf', 10)),
    'USER',
    true
);

INSERT INTO public.categories (id, name, slug, sort_order)
VALUES (1, '개발', 'dev', 1), (2, '디자인', 'design', 2);

INSERT INTO public.posts (id, user_id, url, title, created_at)
SELECT
    ('00000000-0000-4000-8000-0000000001' || lpad(n::text, 2, '0'))::uuid,
    '00000000-0000-4000-8000-000000000001',
    'https://example.invalid/post/' || n,
    title,
    timestamptz '2026-01-01 00:00:00+00' + (n || ' minutes')::interval
FROM (VALUES
    (1, 'Spring Boot 입문'),
    (2, 'Spring 소개'),
    (3, 'Boot 소개'),
    (4, 'C++ 입문'),
    (5, 'C 언어 입문'),
    (6, '리액트 성능 최적화'),
    (7, 'a=b 표기법'),
    (8, '대괄호 [태그] 글'),
    (9, '파이프 a|b 글'),
    (10, '100% 완성'),
    (11, 'a&b 표기법'),
    (12, 'a,b 표기법')
) AS t(n, title);

INSERT INTO public.post_categories (post_id, category_id)
SELECT id, 1 FROM public.posts;

-- account-purge가 실제로 돌았는지 확인하는 표식. 창이 끝난 행이라 그 작업이 지운다.
INSERT INTO public.auth_rate_limits (bucket_key, window_start, hit_count)
VALUES ('experiment-expired', timestamptz '2020-01-01 00:00:00+00', 1);
