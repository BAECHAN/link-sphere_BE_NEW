#!/usr/bin/env bash
# 워크트리 부트스트랩 — 메인 체크아웃의 gitignore된 설정 파일 2개를 지금 워크트리로 복사한다.
# 워크트리 안 어디서든 실행한다: ./scripts/worktree-bootstrap.sh
#
# 왜 스크립트인가: 2026-11-16부터 Claude 세션은 이 두 파일의 이름이 든 명령을 실행하지 못한다(사용자 설정 deny와
# claude-base bash-guard G6 — 값을 가리려던 sed가 키 3개를 대화에 출력한 일로 넣었다). 복사만 하는 정해진 통로를
# 여기 하나 둔다. 이 스크립트는 파일 내용을 출력하지 않는다 — 내용을 읽거나 찍는 줄을 더하지 않는다.
set -euo pipefail

FILES=(application-secret.yml firebase-service-account.json)
RESOURCES=src/main/resources

worktree_root="$(git rev-parse --show-toplevel)"
main_root="$(cd "$(git rev-parse --git-common-dir)/.." && pwd -P)"

if [ "$(cd "${worktree_root}" && pwd -P)" = "${main_root}" ]; then
  echo "메인 체크아웃이다 - 복사할 것이 없다(워크트리 안에서 실행한다)." >&2
  exit 1
fi

for name in "${FILES[@]}"; do
  if [ ! -f "${main_root}/${RESOURCES}/${name}" ]; then
    echo "메인 체크아웃에 ${RESOURCES}/${name}이 없다 - README \"환경 설정\"대로 먼저 만든다." >&2
    exit 1
  fi

  cp -p "${main_root}/${RESOURCES}/${name}" "${worktree_root}/${RESOURCES}/${name}"
  echo "복사함: ${RESOURCES}/${name}"
done
