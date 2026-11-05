#!/usr/bin/env bash
# offset과 keyset(cursor)을 합성 데이터로 비교한다. 운영 DB에는 연결하지 않는다.
# 임시 pgvector 컨테이너를 띄워 실행하고 끝나면 지운다(포트를 열지 않는다).
#
#   ./scripts/pagination-experiment/run.sh > results.md        # 전체(1만·10만·100만 행)
#   PERF_SIZES="10000" SKIP_GAPS=1 ./scripts/pagination-experiment/run.sh   # 빠른 확인
#
# 결과 표는 stdout, 진행 상황은 stderr로 나온다.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE="pgvector/pgvector:pg17"
NAME="pagination-experiment-$$"
PERF_SIZES="${PERF_SIZES:-10000 100000 1000000}"
SKIP_GAPS="${SKIP_GAPS:-}"

cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d --name "$NAME" --shm-size=512m -e POSTGRES_HOST_AUTH_METHOD=trust "$IMAGE" >/dev/null

# initdb가 끝난 뒤 서버가 한 번 재시작하므로 TCP 쪽 준비를 기다린다.
until docker exec "$NAME" pg_isready -q -h 127.0.0.1 -U postgres; do
  sleep 1
done

run_psql() {
  docker exec -i "$NAME" psql -U postgres -X -q "$@"
}

for rows in $PERF_SIZES; do
  echo "[performance] rows=$rows $(date +%H:%M:%S)" >&2
  run_psql -v rows="$rows" -f - <"$DIR/performance.sql" >&2
done

run_psql -At -f - <"$DIR/performance-report.sql"

if [[ -z "$SKIP_GAPS" ]]; then
  echo "[gaps] $(date +%H:%M:%S)" >&2
  run_psql -At -f - <"$DIR/gaps.sql"
fi

echo "[done] $(date +%H:%M:%S)" >&2
