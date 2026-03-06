plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    id("org.springframework.boot") version "3.5.8"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.1.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

// Lambda는 fat JAR(shadowJar)로 배포한다. bootJar와 plain jar는 사용하지 않는다.
tasks.bootJar { enabled = false }
tasks.jar { archiveClassifier.set("") }

tasks.assemble {
    dependsOn("shadowJar")
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.example.linksphere.LinkSphereBeApplicationKt"
    }

    // META-INF/services/** 병합 (SPI 서비스 로더용)
    mergeServiceFiles()

    // mergeServiceFiles()는 spring.factories를 처리하지 않는다.
    // spring.factories에는 ApplicationContextFactory 등 Spring Boot 핵심 구현체가 등록되어 있으며,
    // 누락되면 ApplicationContext 생성 시 비웹 컨텍스트로 폴백된다.
    // Spring XML 네임스페이스 핸들러(handlers, schemas)와 자동 설정 목록도 동일하게 append로 병합한다.
    append("META-INF/spring.factories")
    append("META-INF/spring.handlers")
    append("META-INF/spring.schemas")
    append("META-INF/spring.tooling")
    append("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
    append("META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports")

    // 2GB 초과 JAR 지원 (firebase-admin 등 포함 시 필요)
    isZip64 = true

    // 서명 파일 제거 (fat JAR에서 원본 서명은 무효)
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    // 불필요한 메타 파일 제거로 JAR 크기 최소화
    exclude("META-INF/LICENSE*")
    exclude("META-INF/NOTICE*")
    exclude("META-INF/DEPENDENCIES")
    exclude("META-INF/maven/**")
    exclude("META-INF/proguard/**")
    // protobuf 정의 파일 (firebase-admin 의존성에서 유입, 런타임 불필요)
    exclude("**/*.proto")
    exclude("google/protobuf/**")
    exclude("google/type/**")
    exclude("google/api/**")
    // Swagger UI 정적 에셋 (API 스펙 JSON만 사용, UI HTML/JS 불필요)
    exclude("META-INF/resources/webjars/**")
}

tasks.build {
    dependsOn("shadowJar")
}

group = "com.example"

version = "0.0.1-SNAPSHOT"

description = "link-sphere_BE"

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }


repositories { mavenCentral() }


dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    runtimeOnly("org.postgresql:postgresql")
    
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // JWT 구현을 위한 JJWT 라이브러리 추가
    implementation("io.jsonwebtoken:jjwt-api:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.5")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.5")

    // SpringDoc OpenAPI (Swagger) 라이브러리 추가 — UI 에셋 제외한 API 스펙만 포함
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.7.0")

    // Jsoup
    implementation("org.jsoup:jsoup:1.17.2")

    // Firebase Admin SDK (FCM 푸시 알림) — FCM만 사용하므로 불필요한 GCP 모듈 제외
    implementation("com.google.firebase:firebase-admin:9.4.2") {
        exclude(group = "com.google.cloud", module = "google-cloud-firestore")
        exclude(group = "com.google.cloud", module = "google-cloud-storage")
        exclude(group = "com.google.cloud", module = "google-cloud-bigquery")
    }
    // firebase-admin이 의존하는 JacksonFactory가 classpath에서 누락되는 문제 해결
    implementation("com.google.http-client:google-http-client-jackson2:1.44.2")

    // Lambda RequestStreamHandler 인터페이스
    implementation("com.amazonaws:aws-lambda-java-core:1.2.3")

    // MockMvc로 DispatcherServlet을 직접 호출하기 위해 필요 (Tomcat 소켓 불사용)
    implementation("org.springframework:spring-test")

    // SnapStart CRaC 지원: 체크포인트 전 Tomcat/HikariCP 소켓을 자동으로 닫고 복원 후 재연결
    // 이 의존성 없이는 열린 소켓 때문에 SnapStart 체크포인트가 State:Failed가 된다
    implementation("org.crac:crac")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> { useJUnitPlatform() }
