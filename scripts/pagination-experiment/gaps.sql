-- 스크롤하는 사이에 데이터가 바뀌면 offset과 keyset이 무엇을 놓치고 무엇을 두 번 보여 주는지 센다.
-- 시간이 아니라 논리만 보는 실험이라, 정렬 값을 k1 컬럼에 미리 계산해 두고 인덱스로 빠르게 돌린다.
--
-- 시나리오 하나: 10만 행에서 50쪽(쪽당 10개)을 읽는다. 쪽 사이마다 쓰기를 w건 넣는다.
-- 두 방식 모두 고유 키까지 넣은 같은 정렬을 쓰고, 같은 시드라 같은 쓰기를 같은 순서로 받는다.
-- 시드 5개의 평균을 낸다. 각 시나리오는 끝날 때 예외로 되돌려 다음 시나리오가 원래 데이터에서 시작한다.
--
-- 중복: 이미 보여 준 글을 다시 보여 준 횟수. 읽은 행은 항상 500개라 "본 글 수 = 500 - 중복"이다.
-- 도달: 처음 목록 기준으로 어디까지 읽었나. 본 글 중 값이 안 바뀐 글의 가장 깊은 처음 순위다
--       (위로 올라와 보인 글이 깊이를 부풀리지 않게 뺀다). 새 글만 보고 있으면 0이다.
-- 안 바뀐 글 누락: 처음 순위가 도달 안쪽이고 값이 그대로인데 끝까지 못 본 글 수. 값이 안 바뀐 글을 건너뛴 것이다.
-- 바뀐 글 누락: 처음 순위가 도달 안쪽인데 값이 바뀌어 자리를 옮기는 바람에 못 본 글 수.
-- 아직 도달하지 않은 글과 지워진 글은 누락이 아니다.
\set ON_ERROR_STOP on
\o /dev/null

create table if not exists gap_results (
  sort_name text,
  write_kind text,
  target text,
  w int,
  mode text,
  seed int,
  duplicates int,
  missing_untouched int,
  missing_touched int,
  reached int
);

truncate gap_results;

drop table if exists gap_items;
create table gap_items (
  id uuid primary key,
  k1 double precision not null,
  created_at timestamptz not null,
  init_rank int
);

-- 정렬 값 뽑기. 검색 점수는 운영 시뮬레이션처럼 동점이 많은 정수 묶음에, 일부만 의미 점수(14~20)가 붙는다.
create or replace function draw_score() returns double precision language sql volatile as $$
  select (array[6, 7, 7, 8, 8, 8, 9, 9, 10, 11, 12, 66, 67, 68, 70, 116])[1 + floor(random() * 16)::int]
    + case when random() < 0.15 then 14 + random() * 6 else 0 end
$$;

-- 정렬별로 k1의 뜻이 다르다. 최신순은 작성 시각, 검색은 점수, 최근 열람순은 마지막 열람 시각(30%만 열람, 나머지 0).
create or replace function reset_items(p_sort text) returns void language plpgsql as $$
begin
  drop index if exists gap_items_sort;
  truncate gap_items;
  perform setseed(0.25);

  -- 10번째마다 앞 글과 같은 시각이라 동점이 생긴다.
  insert into gap_items (id, k1, created_at)
  select
    md5('gap' || n)::uuid,
    case p_sort
      when 'latest' then extract(epoch from c.at)
      when 'search' then draw_score()
      when 'viewed' then case when random() < 0.3 then random() * 3700000 else 0 end
    end,
    c.at
  from generate_series(1, 100000) n
  cross join lateral (select timestamptz '2026-01-01 00:00:00+09' + make_interval(secs => (n - (n % 10 = 0)::int) * 37) as at) c;

  update gap_items g set init_rank = r.rn
  from (select id, row_number() over (order by k1 desc, created_at desc, id desc) as rn from gap_items) r
  where g.id = r.id;

  create index gap_items_sort on gap_items (k1 desc, created_at desc, id desc);
  analyze gap_items;
end $$;

create or replace function run_scenario(p_sort text, p_write text, p_target text, p_w int, p_mode text, p_seed int)
returns void language plpgsql as $$
declare
  shown uuid[] := '{}';
  touched uuid[] := '{}';
  targets uuid[];
  target_pos int := 0;
  clock_seq int := 0;
  last_k1 double precision;
  last_created timestamptz;
  last_id uuid;
  r record;
  page int;
  i int;
  v_duplicates int;
  v_missing_untouched int;
  v_missing_touched int;
  v_reached int;
  t uuid;
begin
  perform setseed(p_seed / 10.0);

  -- 쓸 대상을 시드로 섞어 고정한다. 두 방식이 같은 글을 같은 순서로 건드리게 하려는 것이다.
  if p_write <> 'insert' then
    select array_agg(id order by md5(id::text || p_seed)) into targets
    from gap_items
    where p_target = 'uniform' or init_rank <= 5000;
  end if;

  begin
    for page in 1..50 loop
      if p_mode = 'offset' then
        for r in
          select id, k1, created_at from gap_items
          order by k1 desc, created_at desc, id desc
          offset (page - 1) * 10 limit 10
        loop
          shown := shown || r.id;
        end loop;
      else
        for r in
          select id, k1, created_at from gap_items
          where page = 1 or (k1, created_at, id) < (last_k1, last_created, last_id)
          order by k1 desc, created_at desc, id desc
          limit 10
        loop
          shown := shown || r.id;
          last_k1 := r.k1;
          last_created := r.created_at;
          last_id := r.id;
        end loop;
      end if;

      exit when page = 50;

      for i in 1..p_w loop
        clock_seq := clock_seq + 1;

        if p_write = 'insert' then
          insert into gap_items (id, k1, created_at)
          values (
            gen_random_uuid(),
            case p_sort
              when 'latest' then 4000000000 + clock_seq
              when 'search' then draw_score()
              when 'viewed' then 0
            end,
            timestamptz '2030-01-01 00:00:00+09' + make_interval(secs => clock_seq)
          );
        else
          target_pos := target_pos + 1;
          t := targets[target_pos];

          if p_write = 'delete' then
            delete from gap_items where id = t;
          else
            update gap_items
            set k1 = case p_sort when 'search' then draw_score() else 4000000000 + clock_seq end
            where id = t;
            touched := touched || t;
          end if;
        end if;
      end loop;
    end loop;

    select cardinality(shown) - count(distinct s) into v_duplicates from unnest(shown) s;

    select coalesce(max(g.init_rank), 0) into v_reached
    from gap_items g
    where g.id = any(shown) and not g.id = any(touched);

    select
      count(*) filter (where not g.id = any(touched)),
      count(*) filter (where g.id = any(touched))
    into v_missing_untouched, v_missing_touched
    from gap_items g
    where g.init_rank <= v_reached and not g.id = any(shown);

    raise exception using errcode = 'UE001';
  exception when sqlstate 'UE001' then
    null;
  end;

  insert into gap_results values (p_sort, p_write, p_target, p_w, p_mode, p_seed, v_duplicates, v_missing_untouched, v_missing_touched, v_reached);
end $$;

do $$
declare
  s text;
  c record;
  w int;
  m text;
  seed int;
begin
  foreach s in array array['latest', 'search', 'viewed'] loop
    perform reset_items(s);

    for c in
      select * from (values
        ('insert', '-'),
        ('delete', 'hot'),
        ('delete', 'uniform'),
        ('change', 'hot'),
        ('change', 'uniform')
      ) as v(write_kind, target)
      -- 최신순의 정렬 값(작성 시각)은 바뀌지 않는다.
      where not (s = 'latest' and write_kind = 'change')
    loop
      foreach w in array array[0, 1, 10, 100] loop
        foreach m in array array['offset', 'keyset'] loop
          for seed in 1..5 loop
            perform run_scenario(s, c.write_kind, c.target, w, m, seed);
          end loop;
        end loop;
      end loop;
    end loop;

    raise notice 'gaps % done', s;
  end loop;
end $$;

\o
\pset footer off

select '## 쪽 사이 쓰기에 따른 중복·누락';
select '';
select '10만 행에서 50쪽(500개) 읽기, 시드 5개 평균. 칸은 `중복 / 안 바뀐 글 누락 / 바뀐 글 누락 / 도달`. hot은 처음 상위 5,000개 안에서, uniform은 10만 개 전체에서 대상을 고른다.';
select '';
select '| 정렬 | 쓰기 | 대상 | 쪽 사이 쓰기 | offset | keyset |';
select '| --- | --- | --- | ---: | --- | --- |';
select '| ' || sort_name || ' | ' || write_kind || ' | ' || target || ' | ' || w
  || ' | ' || round(avg(duplicates) filter (where mode = 'offset'), 1) || ' / ' || round(avg(missing_untouched) filter (where mode = 'offset'), 1) || ' / ' || round(avg(missing_touched) filter (where mode = 'offset'), 1) || ' / ' || round(avg(reached) filter (where mode = 'offset'))
  || ' | ' || round(avg(duplicates) filter (where mode = 'keyset'), 1) || ' / ' || round(avg(missing_untouched) filter (where mode = 'keyset'), 1) || ' / ' || round(avg(missing_touched) filter (where mode = 'keyset'), 1) || ' / ' || round(avg(reached) filter (where mode = 'keyset'))
  || ' |'
from gap_results
group by sort_name, write_kind, target, w
order by
  case sort_name when 'latest' then 0 when 'search' then 1 else 2 end,
  case write_kind when 'insert' then 0 when 'delete' then 1 else 2 end,
  target, w;
select '';
