# 가벼운 JDK 17 Alpine 이미지 사용
FROM eclipse-temurin:17-jdk-alpine
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar

# 로컬 포트 51119 노출
EXPOSE 51119

# Spring Boot 실행 (포트 및 컨텍스트 경로 명시 권장)
ENTRYPOINT ["java", "-jar", "/app.jar", "--server.port=51119", "--server.servlet.context-path=/api"]