# 📜 변경 이력 (History)

프로젝트의 주요 변경 사항과 커밋 기록을 관리합니다.

## 2026-03-11

- **feat(post)**: 포스트 검색에 태그 배열 검색 추가 (`1c29770`)
- **docs**: DEPLOY.md 시행착오 7·8번 추가 (Security 필터 미적용, HikariCP 연결 문제) (`e1480ec`)
- **fix**: addFilters 제네릭 타입 명시 추가 (컴파일 에러 수정) (`af9328a`)
- **fix**: HikariCP SnapStart 안정성 개선 — `keepalive-time`, `connection-test-query`, `allow-pool-suspension` 설정 (`0d5ae38`)
- **fix**: MockMvc에 Spring Security 필터 체인 명시 추가 (`springSecurityFilterChain` 빈 직접 등록) (`818a279`)

## 2026-02-28

- **feat**: `GlobalExceptionHandler`에 `IllegalArgumentException` 핸들러 추가 (`f79c6d2`)
- **refactor**: SSE 인프라 전체 제거 (`155cf23`)
- **docs**: App Runner 배포 가이드 파일명 변경 (`DEPLOY_WHEN_APP_RUNNER.md`) (`963defc`)
- **fix**: Lambda 환경 안정성 개선 (`e23fa0e`)
- **docs**: Lambda SnapStart 배포 가이드 문서 작성 (`DEPLOY.md`) (`06679cc`)
- **refactor**: AWS Lambda 배포 구성 전환 — MockMvc 방식, Shadow JAR, SnapStart, `LambdaHandler` 구현 (`8f0213a`)

## 2026-02-25

- **feat**: 댓글·답글 작성 시 FCM 푸시 알림 전송 (`a746636`)
- **feat**: `infra/fcm/` 패키지 추가 — FCM 토큰 등록/해제 API, 알림 전송 서비스 (`a746636`)
- **feat**: JWT 액세스 토큰 기본 유효 기간 15분 → 1시간으로 연장 (`ad71103`)
- **feat**: 게시글 수정 API (`PATCH /post/{id}`) 추가, 게시글 생성 시 제목 직접 지정 가능 (`6085f95`)
- **feat(comment)**: 댓글 이미지 업로드 기능 및 Supabase Storage 연동 (`7c24f5f`)
- **feat**: `InvalidTokenException` 도입 및 리프레시 토큰 예외 처리 강화 (`bcca3ed`)

## 2026-02-22

- **feat(post)**: 게시물 조회 시 닉네임 부분 일치 검색 기능 필터 추가 (`1b716f7`)
- **feat(post)**: 게시글 비공개(`isPrivate`) 기능 추가 및 본인글/비공개글 필터링 쿼리 적용, 상태 변경 토글 API 구현 (`52a3c2f`)
- **chore**: 애플리케이션 설정 파일 분리 및 내용 업데이트 (`c85c3b0`)
- **ci**: 앱 배포 워크플로우 개선 (`362ffea`) 및 사용하지 않는 자동 기록 머지 워크플로우 제거 (`399c815`)

## 2026-02-21

- **feat(comment)**: 댓글 수정 API (`PATCH /comment/{id}`) 구현 및 작성자 검증 로직 추가
- **feat(security)**: CORS 허용 메서드에 `PATCH` 추가로 403 오류 해결
- **docs**: 리전 최적화(도쿄) 성능 지표 추가 및 사용 서비스 상세 기입 (README, DEPLOY)
- **chore(deploy)**: GitHub Actions 배포 워크플로우 최적화 (`paths` 허용 리스트 방식 적용)

## 2026-02-20

- **feat(database)**: HikariCP 연결 설정 최적화 (`825e6f2`)
- **feat(post)**: YouTube oEmbed 메타데이터 조회 기능 추가 (`e4d09d8`)
- **fix(auth)**: SameSite 쿠키 속성 'None'으로 변경 및 포워딩 헤더 전략 추가 (`cad0d6e`)
- **chore(deploy)**: GitHub Actions에서 App Runner 배포 단계 제거 (`20d6570`)
- **feat(security)**: CORS 허용 출처 설정 (`02aa2a6`)
- **feat(security)**: Actuator 엔드포인트 접근 허용 (`2be5c33`)
- **feat(actuator)**: Spring Boot Actuator 추가 및 헬스 체크 설정 (`c0ebe63`)
- **chore(deploy)**: ECR 리포지토리 이름 업데이트 (`df2589f`)
- **feat(docker)**: Dockerfile 이름 변경 (`12d4863`)
- **chore(docker)**: .dockerignore 추가 (`a734394`)
- **feat(docker)**: AWS App Runner 배포를 위한 Dockerfile 및 GitHub Actions 추가 (`509b7ab`)

## 2026-02-18

- **feat(comment)**: 댓글 CRUD 구현 및 게시글 통계 통합 (`c62f804`)
- **feat(interaction)**: 좋아요/북마크 상호작용 컨트롤러 추가 (`5c17631`)
- **feat(post)**: 게시글 검색 및 카테고리 필터링 구현 (`c14549e`)
- **refactor**: 게시글 상호작용 데이터를 stats와 userInteractions로 그룹화 (`c2fad07`)
