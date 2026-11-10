#!/usr/bin/env bash
# 운영 요청 경로 후보를 표준 Tomcat과 비교한다. 운영 DB·운영 사이트·AWS에는 연결하지 않는다.
# 더미 환경변수와 임시 Postgres만 쓰고, 외부 API 주소는 컨테이너 안에서 127.0.0.1로 돌린다.
#
#   ./scripts/serving-path-experiment/run.sh                    # 빌드부터 전체
#   WORK=/tmp/x SKIP_BUILD=1 ./scripts/serving-path-experiment/run.sh   # 빌드 재사용
#   GATE=1 ./scripts/serving-path-experiment/run.sh             # PR 검사: 커밋된 HEAD를 Tomcat과 비교
#
# GATE=1이면 HEAD 하나를 빌드해 Tomcat(LinkSphereBeApplicationKt)과 LambdaHandler 두 대상으로 띄운다. 기대값 실패가 있거나
# Tomcat과의 차이가 accepted-differences.json과 다르면 종료 코드 1이다(.github/workflows/serving-path.yml).
#
# 결과 표는 stdout, 진행 상황은 stderr로 나온다. 계획: docs/plans/2026-11-07-serving-path-comparison.md
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(git -C "$DIR" rev-parse --show-toplevel)"
WORK="${WORK:-$(mktemp -d)}"
BASE="$(cat "$DIR/poc/BASE")"
# #177(FunctionUrlQuery) 바로 전 커밋. 하네스가 + 차이를 잡아내는지 보는 대조군이다
CONTROL_COMMIT="a220e25"
IMAGE="public.ecr.aws/lambda/java:17"
ADAPTER_IMAGE="public.ecr.aws/awsguru/aws-lambda-adapter:1.1.0"
NETWORK="serving-exp"
TARGETS="${TARGETS:-tomcat mockmvc candidate-a candidate-b control}"
# Tomcat 대상이 쓰는 빌드. GATE=1이면 HEAD 빌드로 바꾼다
TOMCAT_CLASSES="base"

ENV_ARGS=(
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/exp
  -e SPRING_DATASOURCE_USERNAME=postgres
  -e SPRING_DATASOURCE_PASSWORD=experiment-db-password
  -e GEMINI_API_KEY=experiment-dummy
  -e YOUTUBE_API_KEY=experiment-dummy
  -e SUPABASE_URL=http://supabase.invalid
  -e SUPABASE_KEY=experiment-dummy
  -e SUPABASE_BUCKET=experiment-dummy
  -e AWS_REGION=ap-northeast-1
  -e AWS_ACCESS_KEY_ID=experiment-dummy
  -e AWS_SECRET_ACCESS_KEY=experiment-dummy
  -e AWS_LAMBDA_FUNCTION_TIMEOUT=120
  -e AWS_LAMBDA_FUNCTION_MEMORY_SIZE=2048
  --add-host=generativelanguage.googleapis.com:127.0.0.1
  --add-host=lambda.ap-northeast-1.amazonaws.com:127.0.0.1
  --add-host=email.ap-northeast-1.amazonaws.com:127.0.0.1
  --add-host=www.googleapis.com:127.0.0.1
  --add-host=oauth2.googleapis.com:127.0.0.1
  --add-host=fcm.googleapis.com:127.0.0.1
)

log() {
  echo "[$(date +%H:%M:%S)] $*" >&2
}

cleanup() {
  docker rm -f serving-db serving-tomcat serving-mockmvc serving-candidate-a serving-candidate-b serving-control serving-head >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

build_variant() { # 이름 커밋 [패치]
  local name="$1" commit="$2" patch="${3:-}"
  local src="$WORK/src/$name"

  log "build $name ($commit)"
  mkdir -p "$src" "$WORK/classes/$name"
  git -C "$ROOT" archive "$commit" | tar -x -C "$src"

  if [[ -n "$patch" ]]; then
    (cd "$src" && patch -p1 -s <"$patch")
  fi

  (cd "$src" && ./gradlew shadowJar -x test -q)
  unzip -q -o "$src"/build/libs/*-all.jar -d "$WORK/classes/$name"
}

prepare_adapter() {
  local name="serving-adapter-extract"

  mkdir -p "$WORK/adapter"
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker create --name "$name" "$ADAPTER_IMAGE" /lambda-adapter >/dev/null
  docker cp "$name:/lambda-adapter" "$WORK/adapter/lambda-adapter"
  docker rm "$name" >/dev/null
  cp "$DIR/images/candidate-a/bootstrap" "$WORK/adapter/bootstrap"
  chmod +x "$WORK/adapter/bootstrap"
  cp "$DIR/images/candidate-a/run.sh" "$WORK/classes/candidate-a/run.sh"
  chmod +x "$WORK/classes/candidate-a/run.sh"
}

start_db() {
  docker rm -f serving-db >/dev/null 2>&1 || true
  docker network create "$NETWORK" >/dev/null 2>&1 || true
  docker run -d --name serving-db --network "$NETWORK" --network-alias db \
    -e POSTGRES_PASSWORD=experiment-db-password pgvector/pgvector:pg17 \
    -c ssl=on \
    -c ssl_cert_file=/etc/ssl/certs/ssl-cert-snakeoil.pem \
    -c ssl_key_file=/etc/ssl/private/ssl-cert-snakeoil.key >/dev/null

  # initdb가 끝난 뒤 서버가 한 번 재시작하므로 TCP 쪽 준비를 기다린다.
  until docker exec serving-db pg_isready -q -h 127.0.0.1 -U postgres; do
    sleep 1
  done

  docker exec serving-db psql -U postgres -X -q -c 'CREATE DATABASE seed_template'
  docker exec -i serving-db psql -U postgres -d seed_template -X -q -v ON_ERROR_STOP=1 <"$ROOT/src/test/resources/db/schema.sql" >/dev/null
  docker exec -i serving-db psql -U postgres -d seed_template -X -q -v ON_ERROR_STOP=1 <"$DIR/seed.sql" >/dev/null
}

# 로그인 실패 한도가 뒤 대상을 429로 바꾸지 않게 대상마다 DB를 새로 만든다
reset_db() {
  docker exec serving-db psql -U postgres -X -q \
    -c 'DROP DATABASE IF EXISTS exp WITH (FORCE)' \
    -c 'CREATE DATABASE exp TEMPLATE seed_template'
}

port_of() {
  case "$1" in
    tomcat) echo 19000 ;;
    mockmvc) echo 19001 ;;
    candidate-a) echo 19002 ;;
    candidate-b) echo 19003 ;;
    control) echo 19004 ;;
    head) echo 19005 ;;
  esac
}

kind_of() {
  if [[ "$1" == "tomcat" ]]; then
    echo http
  else
    echo rie
  fi
}

start_target() { # 이름 [docker 인자...]
  local name="$1"
  shift
  local container="serving-$name"
  local port
  port="$(port_of "$name")"

  docker rm -f "$container" >/dev/null 2>&1 || true

  case "$name" in
    tomcat)
      docker run -d --name "$container" --network "$NETWORK" -p "$port:8080" "${ENV_ARGS[@]}" "$@" \
        -v "$WORK/classes/$TOMCAT_CLASSES:/var/task:ro" --entrypoint /var/lang/bin/java "$IMAGE" \
        -cp /var/task com.example.linksphere.LinkSphereBeApplicationKt \
        --server.port=8080 \
        --logging.structured.format.console=logstash \
        --logging.structured.json.customizer=com.example.linksphere.global.common.EmailMaskingLogCustomizer \
        --spring.datasource.hikari.data-source-properties.sslmode=require >/dev/null
      ;;
    mockmvc)
      docker run -d --name "$container" --network "$NETWORK" -p "$port:8080" "${ENV_ARGS[@]}" "$@" \
        -v "$WORK/classes/base:/var/task:ro" "$IMAGE" com.example.linksphere.LambdaHandler::handleRequest >/dev/null
      ;;
    control)
      docker run -d --name "$container" --network "$NETWORK" -p "$port:8080" "${ENV_ARGS[@]}" "$@" \
        -v "$WORK/classes/control:/var/task:ro" "$IMAGE" com.example.linksphere.LambdaHandler::handleRequest >/dev/null
      ;;
    candidate-b)
      docker run -d --name "$container" --network "$NETWORK" -p "$port:8080" "${ENV_ARGS[@]}" "$@" \
        -v "$WORK/classes/candidate-b:/var/task:ro" "$IMAGE" com.example.linksphere.LambdaHandler::handleRequest >/dev/null
      ;;
    head)
      docker run -d --name "$container" --network "$NETWORK" -p "$port:8080" "${ENV_ARGS[@]}" "$@" \
        -v "$WORK/classes/head:/var/task:ro" "$IMAGE" com.example.linksphere.LambdaHandler::handleRequest >/dev/null
      ;;
    candidate-a)
      docker run -d --name "$container" --network "$NETWORK" -p "$port:8080" "${ENV_ARGS[@]}" "$@" \
        -e AWS_LAMBDA_EXEC_WRAPPER=/opt/bootstrap \
        -e AWS_LWA_PORT=8081 \
        -e AWS_LWA_READINESS_CHECK_PROTOCOL=tcp \
        -e AWS_LWA_PASS_THROUGH_PATH=/api/internal/lambda-events \
        -e AWS_LWA_SNAPSTART_BEFORE_CHECKPOINT_PATH=/api/internal/snapstart/before \
        -e AWS_LWA_SNAPSTART_AFTER_RESTORE_PATH=/api/internal/snapstart/after \
        -e AWS_LWA_ERROR_STATUS_CODES=599 \
        -v "$WORK/classes/candidate-a:/var/task:ro" \
        -v "$WORK/adapter/bootstrap:/opt/bootstrap:ro" \
        -v "$WORK/adapter/lambda-adapter:/opt/extensions/lambda-adapter:ro" \
        "$IMAGE" run.sh >/dev/null
      ;;
  esac
}

wait_ready() { # 이름 결과 디렉터리
  if ! compare "$1" ready "$2"; then
    log "$1: 기동 실패"
    docker logs --tail 40 "serving-$1" 2>&1 | cut -c1-300 >&2
    exit 1
  fi
}

compare() { # 이름 phase 결과 디렉터리
  python3 "$DIR/compare.py" run --target "$1" --kind "$(kind_of "$1")" --port "$(port_of "$1")" \
    --container "serving-$1" --phase "$2" --out "$3/$1.json"
}

run_target() { # 이름
  local name="$1" raw="$WORK/raw"

  [[ "$name" == "control" ]] && raw="$WORK/raw-control"
  mkdir -p "$raw"
  rm -f "$raw/$name.json"

  log "$name: 비교 시작"
  reset_db
  start_target "$name"
  wait_ready "$name" "$raw"
  compare "$name" main "$raw"

  if [[ "$name" == "control" ]]; then
    docker rm -f "serving-$name" >/dev/null
    return
  fi

  if [[ "$name" == "candidate-a" ]]; then
    compare "$name" hooks "$raw"
  fi

  docker rm -f "serving-$name" >/dev/null

  # DB 중단: 재기동 시간을 줄이려고 연결 대기를 2초로 줄인다(대상 모두 같게)
  log "$name: DB 중단"
  reset_db
  start_target "$name" -e SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT=2000
  wait_ready "$name" "$raw"
  compare "$name" db-down "$raw"
  compare "$name" db-up-again "$raw"
  docker rm -f "serving-$name" >/dev/null

  log "$name: DB 없이 기동"
  reset_db
  docker stop serving-db >/dev/null
  start_target "$name" -e SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT=2000
  compare "$name" db-boot "$raw"
  docker start serving-db >/dev/null

  until docker exec serving-db pg_isready -q -h 127.0.0.1 -U postgres; do
    sleep 1
  done

  compare "$name" db-boot-again "$raw"
  docker rm -f "serving-$name" >/dev/null
}

check_control() {
  python3 - "$WORK/raw/tomcat.json" "$WORK/raw/mockmvc.json" "$WORK/raw-control/control.json" <<'PY'
import json
import sys

tomcat, mockmvc, control = (json.load(open(path))['records']['q-plus']['response']['body'] for path in sys.argv[1:4])
mockmvc_same = tomcat == mockmvc
control_differs = tomcat != control
print(f'- 현재 MockMvc의 `q-plus`가 Tomcat과 같은가: {mockmvc_same}')
print(f'- #177 이전 코드(대조군)의 `q-plus`가 Tomcat과 다른가: {control_differs}')
sys.exit(0 if mockmvc_same and control_differs else 1)
PY
}

if [[ -n "${GATE:-}" ]]; then
  if [[ -z "${SKIP_BUILD:-}" ]]; then
    build_variant head HEAD
  fi

  TOMCAT_CLASSES="head"
  start_db

  for target in tomcat head; do
    run_target "$target"
  done

  python3 "$DIR/compare.py" report --dir "$WORK/raw" --gate "$DIR/accepted-differences.json"
  exit 0
fi

if [[ -z "${SKIP_BUILD:-}" ]]; then
  build_variant base "$BASE"
  build_variant control "$CONTROL_COMMIT"
  build_variant candidate-a "$BASE" "$DIR/poc/candidate-a.patch"
  build_variant candidate-b "$BASE" "$DIR/poc/candidate-b.patch"
fi

prepare_adapter
start_db

for target in $TARGETS; do
  run_target "$target"
done

echo "## 하네스 검증(대조군)"
echo
check_control
echo
echo "## 비교 결과"
echo
python3 "$DIR/compare.py" report --dir "$WORK/raw"
echo
echo "### 코드 크기(결론에 쓰지 않음)"
echo

for name in base candidate-a candidate-b; do
  echo "- $name JAR: $(cat "$WORK/src/$name"/build/libs/*-all.jar | wc -c | tr -d ' ')바이트"
done

for name in candidate-a candidate-b; do
  echo "- $name 패치: $(git -C "$ROOT" apply --numstat "$DIR/poc/$name.patch" | awk '{added += $1; removed += $2} END {print "+" added " / -" removed}')줄"
done
