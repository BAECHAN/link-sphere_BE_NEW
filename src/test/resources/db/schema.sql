-- 통합 테스트(@Tag("integration"))용 스키마 - 운영 Supabase Postgres의 public 스키마 덤프를 그대로 쓴다.
-- 테스트 컨테이너(pgvector/pgvector:pg17)가 뜰 때 Testcontainers가 이 파일을 실행한다.
--
-- 덤프: 2026-10-14, 운영 서버 PostgreSQL 17.6, pg_dump 17.11
--   docker run --rm -e PGPASSWORD postgres:17 pg_dump "postgresql://<사용자>@<풀러 호스트>:5432/postgres"
--     --schema-only --schema=public --no-owner --no-privileges
--   (5432는 Supavisor 세션 모드. 운영 앱이 쓰는 6543 트랜잭션 모드가 아니다)
--
-- 덤프에서 뺀 것(평범한 Postgres 컨테이너에서 실행되지 않거나 필요 없는 것)
--   \restrict / \unrestrict  - pg_dump 17이 덤프마다 넣는 psql 전용 보호 명령(무작위 키). JDBC로는 실행되지 않는다
--   CREATE SCHEMA public      - 컨테이너에 이미 있다
--
-- 덤프 뒤 손으로 넣은 것(읽기 전용 계정 adhoc_readonly는 members 권한이 없어 pg_dump가 막힌다 - BE docs/DEPLOY.md §12)
--   2026-10-26 bookmark_folders.pinned_at - add_bookmark_folders_pinned_at.sql을 운영에 적용한 뒤 pg_catalog로 7번째 컬럼·
--              timestamp without time zone·NULL 허용·기본값 없음을 확인하고 pg_dump가 내는 형식대로 넣었다
--
-- 아래 "테스트 전용 앞부분"은 운영(Supabase)에만 있는 것을 같은 이름으로 만들어 덤프 본문을 고치지 않는다
--   extensions 스키마의 vector  - posts.embedding이 extensions.vector(768)다
--   auth.uid()                  - bookmarks.user_id의 DEFAULT다(앱은 항상 user_id를 넣으므로 NULL을 돌려줘도 된다)
--   역할의 search_path          - 앱 역할 postgres(풀러 사용자 postgres.<ref>)가 "$user", public, extensions라 운영 쿼리는
--                                 vector·<=>를 스키마 없이 쓴다(PostSearchQuery.resolveSemanticMatches). 2026-10-31 운영
--                                 pg_db_role_setting으로 확인했다(SQL Editor 조회, 사용자 실행)
--
-- 갱신: 운영 스키마를 src/main/resources/sql/ 파일로 바꾸면 위 명령으로 다시 덤프해 아래 본문을 통째로 바꾸고,
-- 머리 주석의 날짜·버전과 "뺀 것"을 다시 확인한다(docs/plans/2026-10-14-integration-test.md). 다시 덤프하면 "손으로 넣은 것"은 지운다.

-- ── 테스트 전용 앞부분 ──────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA extensions;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS 'SELECT NULL::uuid';
ALTER ROLE CURRENT_USER SET search_path = "$user", public, extensions;

-- ── 운영 덤프 본문 ──────────────────────────────────────────────
--
-- PostgreSQL database dump
--


-- Dumped from database version 17.6
-- Dumped by pg_dump version 17.11 (Debian 17.11-1.pgdg13+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--



--
-- Name: SCHEMA public; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA public IS 'standard public schema';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: auth_rate_limits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_rate_limits (
    bucket_key character varying(200) NOT NULL,
    window_start timestamp with time zone NOT NULL,
    hit_count integer DEFAULT 0 NOT NULL
);


--
-- Name: bookmark_folder_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bookmark_folder_items (
    user_id uuid NOT NULL,
    post_id uuid NOT NULL,
    folder_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: bookmark_folders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bookmark_folders (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    name character varying(100) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    pinned_at timestamp without time zone
);


--
-- Name: bookmarks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bookmarks (
    user_id uuid DEFAULT auth.uid() NOT NULL,
    post_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.categories (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    slug character varying(100) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT now()
);


--
-- Name: categories_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: categories_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.categories_id_seq OWNED BY public.categories.id;


--
-- Name: comment_reactions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.comment_reactions (
    user_id uuid NOT NULL,
    comment_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: comments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.comments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    post_id uuid NOT NULL,
    user_id uuid NOT NULL,
    parent_id uuid,
    content text NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    link_url text,
    link_title text,
    link_description text,
    link_og_image text
);


--
-- Name: fcm_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fcm_tokens (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    token text NOT NULL,
    platform character varying(10) DEFAULT 'WEB'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    session_family_id uuid
);


--
-- Name: feed_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.feed_items (
    id uuid NOT NULL,
    source_id uuid,
    post_id uuid,
    normalized_url text NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: feed_sources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.feed_sources (
    id uuid NOT NULL,
    name character varying(100) NOT NULL,
    url text NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    last_fetched_at timestamp without time zone,
    last_error text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: link_previews; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.link_previews (
    url_hash character varying(64) NOT NULL,
    url text NOT NULL,
    title text NOT NULL,
    description text,
    og_image text,
    tags text[],
    page_content text,
    fetched_at timestamp with time zone NOT NULL
);


--
-- Name: member_action_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.member_action_tokens (
    id uuid NOT NULL,
    member_id uuid NOT NULL,
    purpose character varying(20) NOT NULL,
    token_hash character(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: member_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.member_sessions (
    id uuid NOT NULL,
    member_id uuid NOT NULL,
    access_token_hash character(64) NOT NULL,
    refresh_token_hash character(64) NOT NULL,
    access_expires_at timestamp with time zone NOT NULL,
    refresh_expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    family_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: members; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.members (
    id uuid NOT NULL,
    email text,
    nickname text,
    image text,
    created_at timestamp with time zone DEFAULT now(),
    password text,
    updated_at timestamp with time zone DEFAULT now(),
    role character varying(20) DEFAULT 'USER'::character varying NOT NULL,
    is_bot boolean DEFAULT false NOT NULL,
    deleted_at timestamp with time zone,
    email_verified boolean DEFAULT true NOT NULL,
    deletion_requested_at timestamp with time zone
);


--
-- Name: post_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.post_categories (
    post_id uuid NOT NULL,
    category_id bigint NOT NULL
);


--
-- Name: post_reactions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.post_reactions (
    user_id uuid NOT NULL,
    post_id uuid NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: post_views; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.post_views (
    user_id uuid NOT NULL,
    post_id uuid NOT NULL,
    viewed_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: posts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.posts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    url text NOT NULL,
    title text NOT NULL,
    description text,
    tags text[],
    og_image text,
    ai_summary text,
    view_count integer DEFAULT 0,
    created_at timestamp with time zone DEFAULT now(),
    ai_status character varying(20) DEFAULT 'NONE'::character varying NOT NULL,
    is_private boolean DEFAULT false NOT NULL,
    embedding extensions.vector(768)
);


--
-- Name: COLUMN posts.is_private; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.posts.is_private IS '게시물 비공개 여부 (false: 전체 공개, true: 나만 보기)';


--
-- Name: categories id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.categories ALTER COLUMN id SET DEFAULT nextval('public.categories_id_seq'::regclass);


--
-- Name: bookmark_folders bookmark_folders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bookmark_folders
    ADD CONSTRAINT bookmark_folders_pkey PRIMARY KEY (id);


--
-- Name: bookmarks bookmarks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bookmarks
    ADD CONSTRAINT bookmarks_pkey PRIMARY KEY (user_id, post_id);


--
-- Name: categories categories_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.categories
    ADD CONSTRAINT categories_name_key UNIQUE (name);


--
-- Name: categories categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.categories
    ADD CONSTRAINT categories_pkey PRIMARY KEY (id);


--
-- Name: categories categories_slug_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.categories
    ADD CONSTRAINT categories_slug_key UNIQUE (slug);


--
-- Name: comments comments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comments
    ADD CONSTRAINT comments_pkey PRIMARY KEY (id);


--
-- Name: fcm_tokens fcm_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fcm_tokens
    ADD CONSTRAINT fcm_tokens_pkey PRIMARY KEY (id);


--
-- Name: fcm_tokens fcm_tokens_token_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fcm_tokens
    ADD CONSTRAINT fcm_tokens_token_key UNIQUE (token);


--
-- Name: feed_items feed_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feed_items
    ADD CONSTRAINT feed_items_pkey PRIMARY KEY (id);


--
-- Name: feed_sources feed_sources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feed_sources
    ADD CONSTRAINT feed_sources_pkey PRIMARY KEY (id);


--
-- Name: member_action_tokens member_action_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.member_action_tokens
    ADD CONSTRAINT member_action_tokens_pkey PRIMARY KEY (id);


--
-- Name: member_sessions member_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.member_sessions
    ADD CONSTRAINT member_sessions_pkey PRIMARY KEY (id);


--
-- Name: auth_rate_limits pk_auth_rate_limits; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_rate_limits
    ADD CONSTRAINT pk_auth_rate_limits PRIMARY KEY (bucket_key, window_start);


--
-- Name: bookmark_folder_items pk_bookmark_folder_items; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bookmark_folder_items
    ADD CONSTRAINT pk_bookmark_folder_items PRIMARY KEY (user_id, post_id, folder_id);


--
-- Name: comment_reactions pk_comment_reactions; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comment_reactions
    ADD CONSTRAINT pk_comment_reactions PRIMARY KEY (user_id, comment_id);


--
-- Name: link_previews pk_link_previews; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.link_previews
    ADD CONSTRAINT pk_link_previews PRIMARY KEY (url_hash);


--
-- Name: post_reactions pk_post_reactions; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.post_reactions
    ADD CONSTRAINT pk_post_reactions PRIMARY KEY (user_id, post_id);


--
-- Name: post_views pk_post_views; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.post_views
    ADD CONSTRAINT pk_post_views PRIMARY KEY (user_id, post_id);


--
-- Name: post_categories post_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.post_categories
    ADD CONSTRAINT post_categories_pkey PRIMARY KEY (post_id, category_id);


--
-- Name: posts posts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.posts
    ADD CONSTRAINT posts_pkey PRIMARY KEY (id);


--
-- Name: members profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.members
    ADD CONSTRAINT profiles_pkey PRIMARY KEY (id);


--
-- Name: bookmark_folders uk_bookmark_folders_user_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bookmark_folders
    ADD CONSTRAINT uk_bookmark_folders_user_id UNIQUE (user_id, id);


--
-- Name: bookmark_folders uk_bookmark_folders_user_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bookmark_folders
    ADD CONSTRAINT uk_bookmark_folders_user_name UNIQUE (user_id, name);


--
-- Name: feed_items uk_feed_items_normalized_url; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feed_items
    ADD CONSTRAINT uk_feed_items_normalized_url UNIQUE (normalized_url);


--
-- Name: feed_sources uk_feed_sources_url; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feed_sources
    ADD CONSTRAINT uk_feed_sources_url UNIQUE (url);


--
-- Name: member_action_tokens uk_member_action_tokens_hash; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.member_action_tokens
    ADD CONSTRAINT uk_member_action_tokens_hash UNIQUE (token_hash);


--
-- Name: member_sessions uk_member_sessions_access; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.member_sessions
    ADD CONSTRAINT uk_member_sessions_access UNIQUE (access_token_hash);


--
-- Name: member_sessions uk_member_sessions_refresh; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.member_sessions
    ADD CONSTRAINT uk_member_sessions_refresh UNIQUE (refresh_token_hash);


--
-- Name: idx_auth_rate_limits_window; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_auth_rate_limits_window ON public.auth_rate_limits USING btree (window_start);


--
-- Name: idx_bookmark_folder_items_folder; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bookmark_folder_items_folder ON public.bookmark_folder_items USING btree (folder_id, post_id);


--
-- Name: idx_bookmark_folders_user_sort; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bookmark_folders_user_sort ON public.bookmark_folders USING btree (user_id, sort_order);


--
-- Name: idx_bookmarks_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bookmarks_user_id ON public.bookmarks USING btree (user_id);


--
-- Name: idx_comment_reactions_comment_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_comment_reactions_comment_user ON public.comment_reactions USING btree (comment_id, user_id);


--
-- Name: idx_comments_parent_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_comments_parent_id ON public.comments USING btree (parent_id);


--
-- Name: idx_comments_post_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_comments_post_id ON public.comments USING btree (post_id);


--
-- Name: idx_fcm_tokens_session_family; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fcm_tokens_session_family ON public.fcm_tokens USING btree (session_family_id);


--
-- Name: idx_fcm_tokens_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fcm_tokens_user_id ON public.fcm_tokens USING btree (user_id);


--
-- Name: idx_link_previews_fetched_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_link_previews_fetched_at ON public.link_previews USING btree (fetched_at);


--
-- Name: idx_member_action_tokens_member_purpose; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_member_action_tokens_member_purpose ON public.member_action_tokens USING btree (member_id, purpose);


--
-- Name: idx_member_sessions_family; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_member_sessions_family ON public.member_sessions USING btree (family_id);


--
-- Name: idx_member_sessions_member; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_member_sessions_member ON public.member_sessions USING btree (member_id);


--
-- Name: idx_members_deletion_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_members_deletion_pending ON public.members USING btree (deletion_requested_at) WHERE ((deletion_requested_at IS NOT NULL) AND (deleted_at IS NULL));


--
-- Name: idx_post_categories_category_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_post_categories_category_id ON public.post_categories USING btree (category_id);


--
-- Name: idx_post_categories_post_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_post_categories_post_id ON public.post_categories USING btree (post_id);


--
-- Name: idx_post_reactions_post_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_post_reactions_post_user ON public.post_reactions USING btree (post_id, user_id);


--
-- Name: idx_post_views_user_viewed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_post_views_user_viewed ON public.post_views USING btree (user_id, viewed_at);


--
-- Name: idx_posts_is_private; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_posts_is_private ON public.posts USING btree (is_private);


--
-- Name: members_email_lower_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX members_email_lower_key ON public.members USING btree (lower(email));


--
-- Name: members_nickname_lower_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX members_nickname_lower_key ON public.members USING btree (lower(nickname));


--
-- Name: bookmarks bookmarks_post_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bookmarks
    ADD CONSTRAINT bookmarks_post_id_fkey FOREIGN KEY (post_id) REFERENCES public.posts(id) ON DELETE CASCADE;


--
-- Name: bookmarks bookmarks_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bookmarks
    ADD CONSTRAINT bookmarks_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.members(id);


--
-- Name: fcm_tokens fcm_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fcm_tokens
    ADD CONSTRAINT fcm_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.members(id) ON DELETE CASCADE;


--
-- Name: bookmark_folder_items fk_bookmark_folder_items_bookmark; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bookmark_folder_items
    ADD CONSTRAINT fk_bookmark_folder_items_bookmark FOREIGN KEY (user_id, post_id) REFERENCES public.bookmarks(user_id, post_id) ON DELETE CASCADE;


--
-- Name: bookmark_folder_items fk_bookmark_folder_items_folder; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bookmark_folder_items
    ADD CONSTRAINT fk_bookmark_folder_items_folder FOREIGN KEY (user_id, folder_id) REFERENCES public.bookmark_folders(user_id, id) ON DELETE CASCADE;


--
-- Name: comment_reactions fk_comment_reactions_comment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comment_reactions
    ADD CONSTRAINT fk_comment_reactions_comment FOREIGN KEY (comment_id) REFERENCES public.comments(id) ON DELETE CASCADE;


--
-- Name: comments fk_comments_parent; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comments
    ADD CONSTRAINT fk_comments_parent FOREIGN KEY (parent_id) REFERENCES public.comments(id) ON DELETE SET NULL;


--
-- Name: comments fk_comments_post; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comments
    ADD CONSTRAINT fk_comments_post FOREIGN KEY (post_id) REFERENCES public.posts(id) ON DELETE CASCADE;


--
-- Name: comments fk_comments_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comments
    ADD CONSTRAINT fk_comments_user FOREIGN KEY (user_id) REFERENCES public.members(id) ON DELETE CASCADE;


--
-- Name: feed_items fk_feed_items_post; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feed_items
    ADD CONSTRAINT fk_feed_items_post FOREIGN KEY (post_id) REFERENCES public.posts(id) ON DELETE SET NULL;


--
-- Name: feed_items fk_feed_items_source; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feed_items
    ADD CONSTRAINT fk_feed_items_source FOREIGN KEY (source_id) REFERENCES public.feed_sources(id) ON DELETE SET NULL;


--
-- Name: member_action_tokens fk_member_action_tokens_member; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.member_action_tokens
    ADD CONSTRAINT fk_member_action_tokens_member FOREIGN KEY (member_id) REFERENCES public.members(id) ON DELETE CASCADE;


--
-- Name: member_sessions fk_member_sessions_member; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.member_sessions
    ADD CONSTRAINT fk_member_sessions_member FOREIGN KEY (member_id) REFERENCES public.members(id) ON DELETE CASCADE;


--
-- Name: post_reactions fk_post_reactions_post; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.post_reactions
    ADD CONSTRAINT fk_post_reactions_post FOREIGN KEY (post_id) REFERENCES public.posts(id) ON DELETE CASCADE;


--
-- Name: post_views fk_post_views_post; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.post_views
    ADD CONSTRAINT fk_post_views_post FOREIGN KEY (post_id) REFERENCES public.posts(id) ON DELETE CASCADE;


--
-- Name: post_categories post_categories_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.post_categories
    ADD CONSTRAINT post_categories_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.categories(id) ON DELETE CASCADE;


--
-- Name: post_categories post_categories_post_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.post_categories
    ADD CONSTRAINT post_categories_post_id_fkey FOREIGN KEY (post_id) REFERENCES public.posts(id) ON DELETE CASCADE;


--
-- Name: posts posts_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.posts
    ADD CONSTRAINT posts_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.members(id) ON DELETE CASCADE;


--
-- Name: auth_rate_limits; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.auth_rate_limits ENABLE ROW LEVEL SECURITY;

--
-- Name: bookmark_folder_items; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.bookmark_folder_items ENABLE ROW LEVEL SECURITY;

--
-- Name: bookmark_folders; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.bookmark_folders ENABLE ROW LEVEL SECURITY;

--
-- Name: bookmarks; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.bookmarks ENABLE ROW LEVEL SECURITY;

--
-- Name: categories; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.categories ENABLE ROW LEVEL SECURITY;

--
-- Name: comment_reactions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.comment_reactions ENABLE ROW LEVEL SECURITY;

--
-- Name: comments; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.comments ENABLE ROW LEVEL SECURITY;

--
-- Name: fcm_tokens; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.fcm_tokens ENABLE ROW LEVEL SECURITY;

--
-- Name: feed_items; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.feed_items ENABLE ROW LEVEL SECURITY;

--
-- Name: feed_sources; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.feed_sources ENABLE ROW LEVEL SECURITY;

--
-- Name: link_previews; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.link_previews ENABLE ROW LEVEL SECURITY;

--
-- Name: member_action_tokens; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.member_action_tokens ENABLE ROW LEVEL SECURITY;

--
-- Name: member_sessions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.member_sessions ENABLE ROW LEVEL SECURITY;

--
-- Name: members; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.members ENABLE ROW LEVEL SECURITY;

--
-- Name: post_categories; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.post_categories ENABLE ROW LEVEL SECURITY;

--
-- Name: post_reactions; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.post_reactions ENABLE ROW LEVEL SECURITY;

--
-- Name: post_views; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.post_views ENABLE ROW LEVEL SECURITY;

--
-- Name: posts; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.posts ENABLE ROW LEVEL SECURITY;

--
-- PostgreSQL database dump complete
--
