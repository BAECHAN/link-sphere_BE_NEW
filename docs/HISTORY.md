# 📜 변경 이력 (History)

프로젝트의 주요 변경 사항과 커밋 기록을 관리합니다.

## 2026-02-21

- **feat(comment)**: 댓글 수정 API (`PATCH /comment/{id}`) 구현 및 작성자 검증 로직 추가
- **feat(security)**: CORS 허용 메서드에 `PATCH` 추가로 403 오류 해결
- **docs**: 리전 최적화(도쿄) 성능 지표 추가 및 사용 서비스 상세 기입 (README, DEPLOY)

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
