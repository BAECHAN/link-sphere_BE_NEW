-- perf_results를 Markdown 표로 찍는다. run.sh가 psql -At로 실행한다.
\set ON_ERROR_STOP on
\pset footer off

select '## 실행 환경';
select '';
select '- ' || split_part(version(), ' on ', 1);
select '- shared_buffers ' || current_setting('shared_buffers') || ', work_mem ' || current_setting('work_mem');
select '';
select '| 행 수 | 표 전체 크기 | 검색 후보 |';
select '| --- | --- | --- |';
select '| ' || rows || ' | ' || total_mb || ' MB | ' || candidates || ' |' from perf_sizes order by rows;
select '';
select '## 같은 위치 같은 결과 확인';
select '';
select '비교한 ' || count(*) || '건 중 결과가 다른 건 ' || count(*) filter (where not same_rows) || '건' from perf_checks;
select '';
select '## 조회 시간(ms, 중앙값) · 읽은 버퍼 · 실행 계획';
select '';
select '| 행 수 | 시나리오 | 방식 | 위치 | ms | 횟수 | 버퍼 | 계획 |';
select '| --- | --- | --- | --- | ---: | ---: | ---: | --- |';
select '| ' || rows || ' | ' || scenario || ' | ' || method || ' | ' || depth || ' | ' || median_ms || ' | ' || runs || ' | ' || buffers || ' | ' || coalesce(plan, '') || ' |'
from perf_results
order by rows, scenario, depth,
  case method
    when 'offset 인덱스 없음' then 0
    when 'offset' then 1
    when 'keyset row-value' then 2
    when 'keyset OR 상한 있음' then 3
    when 'keyset OR 상한 없음' then 4
    when 'keyset 서브쿼리' then 5
    else 6
  end;
select '';
