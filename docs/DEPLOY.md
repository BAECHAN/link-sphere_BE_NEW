# 🚀 배포 가이드 (Deployment Guide)

이 프로젝트는 **GitHub Actions**, **AWS ECR**, **AWS App Runner**를 사용하여 CI/CD 파이프라인을 구축했습니다.

## 🏗️ 배포 아키텍처

1. **GitHub Push**: `main` 브랜치에 코드가 푸시됩니다.
2. **GitHub Actions**: 자동으로 워크플로우가 트리거되어 빌드 및 테스트를 수행합니다.
3. **AWS ECR Upload**: 빌드된 Docker 이미지를 AWS Elastic Container Registry (ECR)에 푸시합니다.
4. **AWS App Runner Deploy**: ECR에 새로운 이미지가 업로드되면, AWS App Runner가 이를 감지하고 자동으로 새 버전을 배포합니다.

### 💡 지역 최적화 (Regional Latency Optimization)

현재 인프라는 **AWS App Runner(도쿄)**와 **Supabase Database(도쿄)**를 같은 리전에 배치하여 최적의 응답 속도를 확보하고 있습니다.

- **서울 리전 DB 사용 시**: API 응답 속도 약 **2.5ms** 소요
- **도쿄 리전 DB 이전 후**: API 응답 속도 약 **0.5ms**로 대폭 감소 (**~80% 개선**)
- 서비스 지연 시간을 최소화하기 위해 애플리케이션과 데이터베이스의 리전을 동일하게 유지하는 것을 권장합니다.

---

## 📋 사전 요구사항 (Prerequisites)

배포를 위해 다음 AWS 리소스가 필요합니다. (리전: `ap-northeast-1` 도쿄 권장)
배포 비용은 거의 안들고 서버 이용 비용만 발생

### 1. AWS ECR (Elastic Container Registry)

- **리포지토리 이름**: `link-sphere/link-sphere-be`
- Docker 이미지를 저장할 공간입니다.
- GitHub Actions에서 이 이름으로 이미지를 푸시하도록 설정되어 있습니다.

### 2. AWS App Runner

- **Source**: Container Registry (ECR)
- **Image URI**: ECR 리포지토리의 `latest` 태그 선택
- **Deployment settings**: Automatic (자동 배포 활성화)
- **Port**: `51119`
- **Health check**: `/actuator/health` (Spring Boot Actuator)

---

## ⚙️ GitHub Actions 설정

`.github/workflows/deploy.yml` 파일에 CI/CD 파이프라인이 정의되어 있습니다.

### 주요 단계

1. **JDK 17 설정**: Java 17 (Temurin) 환경 구성
2. **Gradle Build**: `./gradlew bootJar` 명령어로 애플리케이션 빌드
3. **AWS Credentials**: GitHub Secrets에 저장된 키로 AWS 인증 (`ap-northeast-1`)
4. **Docker Build & Push**: Docker 이미지를 빌드하고 ECR로 푸시 (`latest` 태그)

### GitHub Secrets 설정

GitHub 저장소의 `Settings > Secrets and variables > Actions`에 다음 변수를 등록해야 합니다.

| Secret Name             | 설명                               |
| ----------------------- | ---------------------------------- |
| `AWS_ACCESS_KEY_ID`     | AWS 액세스 키 (ECR 쓰기 권한 필요) |
| `AWS_SECRET_ACCESS_KEY` | AWS 시크릿 액세스 키               |

---

## ☁️ AWS App Runner 설정 (상세)

App Runner 서비스 생성 시 다음 설정을 따릅니다.

1. **Source & deployment**
   - **Repository type**: ECR Public 또는 Private (Private 권장)
   - **Source image URI**: `[AWS_ACCOUNT_ID].dkr.ecr.ap-northeast-1.amazonaws.com/link-sphere/link-sphere-be:latest`
   - **Deployment settings**:
     - **Trigger**: **Automatic** (중요: ECR에 새 이미지가 푸시될 때마다 자동 배포)
     - **ECR access role**: `AppRunnerECRAccessRole` (새로 생성하거나 기존 역할 선택)

2. **Service configuration**
   - **Service name**: `link-sphere-be` (예시)
   - **Virtual CPU & Memory**: 필요에 따라 설정 (예: 1 vCPU, 2 GB)
   - **Environment variables**:
     - `SPRING_PROFILES_ACTIVE`: `prod` (필요 시)
     - `SPRING_DATASOURCE_URL`: DB 연결 주소
     - `SPRING_DATASOURCE_USERNAME`: DB 사용자명
     - `SPRING_DATASOURCE_PASSWORD`: DB 비밀번호
     - `GEMINI_API_KEY`: Gemini API 키
     - `JWT_SECRET`: JWT 시크릿 키
   - **Port**: `51119`

---

## 🛠️ 로컬 테스트 (Docker)

로컬 환경에서 배포 이미지를 미리 테스트할 수 있습니다.

```bash
# 1. 빌드 (테스트 제외)
./gradlew clean build -x test

# 2. Docker 이미지 생성
docker build -t linksphere-be .

# 3. 컨테이너 실행
docker run -p 51119:51119 linksphere-be
```
