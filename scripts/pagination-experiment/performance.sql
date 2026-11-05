-- offset과 keyset의 조회 비용을 행 수별로 잰다. run.sh가 -v rows=<행 수>로 실행한다.
-- 표는 운영 posts와 같은 컬럼이고, 글 크기는 운영 평균(2026-11-05 읽기 계정 조회)에 맞췄다:
-- 제목 74, 설명 167, AI 요약 1,024, 태그 66바이트(6개), 임베딩 3,076바이트, 공개 92.9%, 임베딩 보유 96%.
\set ON_ERROR_STOP on
\o /dev/null

select set_config('exp.rows', :'rows', false);

create extension if not exists vector;

create table if not exists perf_results (
  rows bigint,
  scenario text,
  method text,
  depth bigint,
  median_ms numeric,
  runs int,
  buffers bigint,
  plan text,
  primary key (rows, scenario, method, depth)
);

create table if not exists perf_sizes (rows bigint primary key, total_mb bigint, candidates bigint);

create table if not exists perf_checks (rows bigint, scenario text, method text, depth bigint, same_rows boolean);

-- 한글 음절과 영문 조각을 섞은 임의 문장. 16진수 조각이라 'react'가 우연히 나오지 않는다.
create or replace function pseudo_text(target_bytes int) returns text language sql volatile as $$
  select string_agg(
    case when random() < 0.2
      then substr(md5(random()::text), 1, 5)
      else chr(44032 + floor(random() * 11172)::int) || chr(44032 + floor(random() * 11172)::int)
    end, ' ')
  from generate_series(1, greatest(1, target_bytes / 7))
$$;

-- 쿼리를 EXPLAIN (ANALYZE, BUFFERS, SERIALIZE)로 실행해 중앙값을 낸다. SERIALIZE는 PG17부터 있고,
-- 이게 없으면 TOAST에 있는 embedding을 꺼내는 비용이 빠진다(앱의 목록 쿼리는 embedding까지 읽는다).
-- 첫 회는 캐시를 데우는 용도로 버린다. 느린 쿼리는 예산(budget_ms)을 넘기면 횟수를 줄이고 runs에 남긴다.
create or replace function measure(
  p_scenario text, p_method text, p_depth bigint, p_query text, budget_ms numeric default 60000
) returns void language plpgsql as $$
declare
  explained jsonb;
  plan_json jsonb;
  times numeric[] := '{}';
  started timestamptz := clock_timestamp();
  total_buffers bigint;
  summary text;
  i int;
begin
  for i in 1..6 loop
    execute 'explain (analyze, buffers, timing off, serialize text, format json) ' || p_query into explained;
    times := times || (explained->0->>'Execution Time')::numeric;
    exit when extract(epoch from clock_timestamp() - started) * 1000 > budget_ms;
  end loop;

  if cardinality(times) > 1 then
    times := times[2:];
  end if;

  plan_json := explained->0->'Plan';
  total_buffers := coalesce((plan_json->>'Shared Hit Blocks')::bigint, 0) + coalesce((plan_json->>'Shared Read Blocks')::bigint, 0)
    + coalesce((explained->0->'Serialization'->>'Shared Hit Blocks')::bigint, 0) + coalesce((explained->0->'Serialization'->>'Shared Read Blocks')::bigint, 0);

  select string_agg(node, ' + ') into summary
  from (
    select coalesce(n->>'Node Type', '')
      || coalesce('(' || (n->>'Index Name') || ')', '')
      || case when n ? 'Index Cond' then ' Index Cond' else '' end
      || case when (n->>'Rows Removed by Filter')::bigint > 0 then ' 버림 ' || (n->>'Rows Removed by Filter') else '' end
      || coalesce(' ' || (n->>'Sort Method') || ' ' || (n->>'Sort Space Used') || 'kB', '') as node
    from jsonb_path_query(plan_json, 'strict $.** ? (exists (@."Node Type"))') n
    where n->>'Node Type' in ('Seq Scan', 'Index Scan', 'Index Only Scan', 'Bitmap Heap Scan', 'Sort', 'Incremental Sort', 'Gather Merge')
  ) nodes;

  insert into perf_results values (
    current_setting('exp.rows')::bigint, p_scenario, p_method, p_depth,
    (select round(percentile_cont(0.5) within group (order by t)::numeric, 2) from unnest(times) t),
    cardinality(times), total_buffers, summary
  )
  on conflict (rows, scenario, method, depth) do update
    set median_ms = excluded.median_ms, runs = excluded.runs, buffers = excluded.buffers, plan = excluded.plan;

  raise notice '% % depth=% -> % ms (n=%)', p_scenario, p_method, p_depth,
    (select round(percentile_cont(0.5) within group (order by t)::numeric, 2) from unnest(times) t), cardinality(times);
end $$;

-- 같은 위치에서 두 방식이 같은 행을 돌려주는지 확인한다. 다르면 비교 자체가 무효다.
create or replace function check_same_rows(p_scenario text, p_method text, p_depth bigint, offset_query text, keyset_query text)
returns void language plpgsql as $$
declare
  a uuid[];
  b uuid[];
begin
  execute 'select array_agg(id) from (' || offset_query || ') x' into a;
  execute 'select (array_agg(id))[1:10] from (' || keyset_query || ') x' into b;
  insert into perf_checks values (current_setting('exp.rows')::bigint, p_scenario, p_method, p_depth, a is not distinct from b);
end $$;

drop table if exists posts;

create table posts (
  id uuid default gen_random_uuid() not null,
  user_id uuid not null,
  url text not null,
  title text not null,
  description text,
  tags text[],
  og_image text,
  ai_summary text,
  view_count integer default 0,
  created_at timestamp with time zone default now(),
  ai_status character varying(20) default 'NONE'::character varying not null,
  is_private boolean default false not null,
  embedding vector(768)
);

select setseed(0.5);

drop table if exists text_pool;
create unlogged table text_pool as
select i,
  pseudo_text(74) as title,
  pseudo_text(167) as description,
  pseudo_text(1024) as ai_summary,
  (select array_agg(substr(md5(random()::text || g), 1, 10)) from generate_series(1, 6) g where i = i) as tags,
  (select array_agg(random()::real - 0.5) from generate_series(1, 768) where i = i)::vector(768) as embedding
from generate_series(0, 1998) i;

create unique index on text_pool (i);

-- 소수로 나눈 나머지로 조합해 내용이 겹치지 않게 한다. 시각은 오래된 글부터 넣어(운영처럼 append) 37초 간격으로
-- 두되, 10번째마다 앞 글과 같은 시각으로 둬 동점을 만든다.
insert into posts (id, user_id, url, title, description, tags, og_image, ai_summary, view_count, created_at, is_private, embedding)
select
  md5('post' || n)::uuid,
  md5('user' || (n % 100))::uuid,
  'https://example.com/' || md5('url' || n),
  case
    when n % 5000 = 0 then 'react'
    when n % 40 = 0 then 'react ' || t.title
    when n % 40 = 20 then 'reactive ' || t.title
    else t.title
  end,
  case when n % 50 = 2 then d.description || ' react 입문' when n % 50 = 27 then d.description || ' reactjs' else d.description end,
  case when n % 20 = 1 then t.tags || 'react'::text else t.tags end,
  'https://example.com/og/' || md5('og' || n) || '.png',
  case when n % 50 = 3 then s.ai_summary || ' react' when n % 50 = 28 then s.ai_summary || ' reactive' else s.ai_summary end,
  n % 200,
  timestamptz '2026-11-05 00:00:00+09' - make_interval(secs => (:rows - (n - (n % 10 = 0)::int)) * 37),
  n % 14 = 0,
  case when n % 25 = 0 then null else e.embedding end
from generate_series(1, :rows) n
join text_pool t on t.i = n % 1999
join text_pool d on d.i = n % 1997
join text_pool s on s.i = n % 1993
join text_pool e on e.i = n % 1987;

alter table posts add primary key (id);
create index idx_posts_is_private on posts using btree (is_private);
vacuum analyze posts;

-- 공통으로 쓰는 값
create or replace function latest_key(p_depth bigint, out created_at timestamptz, out id uuid) language sql stable as $$
  select created_at, id from posts where is_private = false order by created_at desc, id desc offset p_depth - 1 limit 1
$$;

create or replace function latest_queries(p_method text, p_depth bigint) returns text language plpgsql stable as $$
declare
  k record;
  x text;
  y text;
begin
  if p_method like 'offset%' then
    return format('select * from posts where is_private = false order by created_at desc, id desc offset %s limit 10', p_depth);
  end if;

  select * into k from latest_key(p_depth);
  x := quote_literal(k.created_at) || '::timestamptz';
  y := quote_literal(k.id) || '::uuid';

  return format('select * from posts where is_private = false and %s order by created_at desc, id desc limit 11',
    case p_method
      when 'keyset row-value' then format('(created_at, id) < (%s, %s)', x, y)
      when 'keyset OR 상한 없음' then format('(created_at < %s or (created_at = %s and id < %s))', x, x, y)
      when 'keyset OR 상한 있음' then format('created_at <= %s and (created_at < %s or (created_at = %s and id < %s))', x, x, x, y)
    end);
end $$;

-- 인덱스가 없는 지금 상태
do $$
declare
  n bigint := current_setting('exp.rows')::bigint;
  public_rows bigint;
  d bigint;
begin
  select count(*) into public_rows from posts where is_private = false;

  foreach d in array array[50, 1000, public_rows / 2] loop
    perform measure('최신순', 'offset 인덱스 없음', d, latest_queries('offset', d));
  end loop;

  perform measure('최신순 count', 'count(distinct id)', 0, 'select count(distinct id) from posts where is_private = false');
end $$;

create index idx_posts_created_at_id on posts (created_at desc, id desc);
vacuum analyze posts;

do $$
declare
  public_rows bigint;
  d bigint;
  m text;
begin
  select count(*) into public_rows from posts where is_private = false;

  foreach d in array array[50, 1000, public_rows / 2] loop
    foreach m in array array['offset', 'keyset row-value', 'keyset OR 상한 있음', 'keyset OR 상한 없음'] loop
      perform measure('최신순', m, d, latest_queries(m, d));

      if m <> 'offset' and public_rows <= 100000 then
        perform check_same_rows('최신순', m, d, latest_queries('offset', d), latest_queries(m, d));
      end if;
    end loop;
  end loop;
end $$;

-- 검색. PostSearchQuery.kt의 relevanceScore·searchPredicate를 토큰 1개('react') 기준으로 그대로 옮겼다.
create or replace function field_like(col text, strip_pattern text, pattern text) returns text language sql immutable as $$
  select '(lower(replace(' || col || ', '' '', '''')) like ' || quote_literal(pattern)
    || ' or regexp_replace(lower(replace(' || col || ', '' '', '''')), ' || quote_literal(strip_pattern) || ', '''', ''g'') like ' || quote_literal(pattern) || ')'
$$;

create or replace function word_like(col text, pattern text) returns text language sql immutable as $$
  select '('' '' || regexp_replace(lower(' || col || '), ''[^abcdefghijklmnopqrstuvwxyz0123456789]'', '' '', ''g'') || '' '' like ' || quote_literal(pattern) || ')'
$$;

-- 의미 매칭 후보 K개. 앱은 임계값 안의 (id, 거리)를 먼저 뽑아 SQL에 CASE·IN 목록으로 싣는다.
create or replace function semantic_case(k int) returns text language sql stable as $$
  select coalesce('case ' || string_agg('when id = ' || quote_literal(id) || '::uuid then ' || ((1 - (0.2 + (row_number::numeric % 10) / 100)) * 20), ' ') || ' else 0.0 end', '0.0')
  from (select id, row_number() over (order by id) as row_number from posts where is_private = false order by id limit k) x
$$;

create or replace function semantic_ids(k int) returns text language sql stable as $$
  select coalesce(string_agg(quote_literal(id) || '::uuid', ','), 'null')
  from (select id from posts where is_private = false order by id limit k) x
$$;

create or replace function search_score(k int) returns text language plpgsql stable as $body$
declare
  strip text := $s$[]!"#$%&'()*+,./:;<=>?@^_`{|}~·…-]$s$;
  strip_tags text := $s$[]!"#$%&'()*+./:;<=>?@^_`{|}~·…-]$s$;
  tags_col text := 'array_to_string(tags, '','')';
  title_m text := field_like('title', strip, '%react%');
  desc_m text := field_like('description', strip, '%react%');
  tags_m text := field_like(tags_col, strip_tags, '%react%');
  summary_m text := field_like('ai_summary', strip, '%react%');
begin
  return '(0'
    || ' + case when ' || title_m || ' then 3 else 0 end'
    || ' + case when ' || tags_m || ' then 2 else 0 end'
    || ' + case when ' || desc_m || ' then 1 else 0 end'
    || ' + case when ' || summary_m || ' then 1 else 0 end'
    || ' + case when ' || title_m || ' or ' || desc_m || ' or ' || tags_m || ' or ' || summary_m || ' then 5 else 0 end'
    || ' + case when ' || word_like('title', '% react %') || ' or ' || word_like('description', '% react %') || ' or ' || word_like(tags_col, '% react %') || ' or ' || word_like('ai_summary', '% react %') || ' then 60 else 0 end'
    || ' + case when lower(replace(title, '' '', '''')) = ''react'' or regexp_replace(lower(replace(title, '' '', '''')), ' || quote_literal(strip) || ', '''', ''g'') = ''react'' then 100'
    ||        ' when lower(replace(title, '' '', '''')) like ''react%'' or regexp_replace(lower(replace(title, '' '', '''')), ' || quote_literal(strip) || ', '''', ''g'') like ''react%'' then 50 else 0 end'
    || ' + ' || semantic_case(k)
    || ')::double precision';
end $body$;

create or replace function search_predicate(k int) returns text language plpgsql stable as $body$
declare
  strip text := $s$[]!"#$%&'()*+,./:;<=>?@^_`{|}~·…-]$s$;
  strip_tags text := $s$[]!"#$%&'()*+./:;<=>?@^_`{|}~·…-]$s$;
begin
  return 'is_private = false and (' || field_like('title', strip, '%react%')
    || ' or ' || field_like('description', strip, '%react%')
    || ' or ' || field_like('array_to_string(tags, '','')', strip_tags, '%react%')
    || ' or ' || field_like('ai_summary', strip, '%react%')
    || ' or id in (' || semantic_ids(k) || '))';
end $body$;

create or replace function search_offset_query(k int, p_depth bigint) returns text language plpgsql stable as $$
begin
  return 'select *, ' || search_score(k) || ' as score_key from posts where ' || search_predicate(k)
    || ' order by 14 desc, created_at desc, id desc offset ' || p_depth || ' limit 10';
end $$;

-- 점수 식은 인덱스가 없어 상한이 범위 탐색에 도움이 안 되고, WHERE에 식을 쓸수록 다시 계산한다. 세 형태를 비교한다.
--   상한 있음: 최신순과 같은 공용 형태. 식이 WHERE에 3번 나온다.
--   상한 없음: 식이 WHERE에 2번 나온다.
--   서브쿼리: 안쪽에서 1번만 계산하고 바깥에서 그 값을 비교한다. OFFSET 0은 PostgreSQL이 서브쿼리를 풀어 식을 다시
--             펼치지 못하게 막는다.
create or replace function search_keyset_query(p_form text, k int, s double precision, c timestamptz, i uuid) returns text language plpgsql stable as $$
declare
  score text := search_score(k);
  sv text := s::text || '::double precision';
  cv text := quote_literal(c) || '::timestamptz';
  iv text := quote_literal(i) || '::uuid';
  tie text := '(created_at < ' || cv || ' or (created_at = ' || cv || ' and id < ' || iv || '))';
  tail text := ' order by 14 desc, created_at desc, id desc limit 11';
begin
  if p_form = 'keyset 서브쿼리' then
    return 'select * from (select *, ' || score || ' as score_key from posts where ' || search_predicate(k) || ' offset 0) x'
      || ' where score_key < ' || sv || ' or (score_key = ' || sv || ' and ' || tie || ')'
      || tail;
  end if;

  return 'select *, ' || score || ' as score_key from posts where ' || search_predicate(k)
    || case when p_form = 'keyset OR 상한 있음' then ' and ' || score || ' <= ' || sv else '' end
    || ' and (' || score || ' < ' || sv
    || ' or (' || score || ' = ' || sv || ' and ' || tie || '))'
    || tail;
end $$;

create or replace function measure_search(p_scenario text, k int, budget_ms numeric, depths bigint[]) returns void language plpgsql as $$
declare
  d bigint;
  s double precision;
  c timestamptz;
  i uuid;
  offset_q text;
  keyset_q text;
  form text;
begin
  foreach d in array depths loop
    execute 'select score_key, created_at, id from (' || search_offset_query(k, d - 1) || ') x limit 1' into s, c, i;
    offset_q := search_offset_query(k, d);
    perform measure(p_scenario, 'offset', d, offset_q, budget_ms);

    foreach form in array array['keyset OR 상한 있음', 'keyset OR 상한 없음', 'keyset 서브쿼리'] loop
      keyset_q := search_keyset_query(form, k, s, c, i);
      perform measure(p_scenario, form, d, keyset_q, budget_ms);

      if current_setting('exp.rows')::bigint <= 100000 then
        perform check_same_rows(p_scenario, form, d, offset_q, keyset_q);
      end if;
    end loop;
  end loop;
end $$;

do $$
declare
  n bigint := current_setting('exp.rows')::bigint;
  candidates bigint;
begin
  execute 'select count(*) from posts where ' || search_predicate(30) into candidates;

  insert into perf_sizes values (n, pg_total_relation_size('posts') / 1024 / 1024, candidates)
  on conflict (rows) do update set total_mb = excluded.total_mb, candidates = excluded.candidates;

  perform measure('검색 K=30', 'count(distinct id)', 0, 'select count(distinct id) from posts where ' || search_predicate(30), 120000);
  perform measure_search('검색 K=30', 30, 120000, array[50, 1000, candidates / 2]);

  -- 임계값 안 후보를 고르는 사전 쿼리. 페이지 방식과 상관없이 쪽마다 돈다(PostSearchQuery.resolveSemanticMatches).
  perform measure('의미 후보 사전 쿼리', '전 행 거리 계산', 0,
    format('select id, embedding <=> %L::vector as distance from posts where embedding is not null and embedding <=> %L::vector < 0.30',
      (select embedding from text_pool where i = 7), (select embedding from text_pool where i = 7)), 120000);

  -- CASE·IN 목록이 길어질 때. 후보가 많아지면 페이지 방식과 무관하게 이 부분이 커진다.
  if n = 100000 then
    perform measure_search('검색 K=1000', 1000, 120000, array[1000]);
  end if;
end $$;

\o
