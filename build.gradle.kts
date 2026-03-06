plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    id("org.springframework.boot") version "3.5.8"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.1.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

// Spring Boot의 bootJar 비활성화 → shadowJar만 사용 (Lambda 배포용)
tasks.bootJar { enabled = false }

// plain jar도 비활성화 (Lambda에 불필요)
tasks.jar { archiveClassifier.set("") }

tasks.assemble {
    dependsOn("shadowJar")
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.example.linksphere.LinkSphereBeApplicationKt"
    }
    mergeServiceFiles()
    // spring.factories는 mergeServiceFiles()가 처리하지 않으므로 명시적으로 append
    // spring-boot.jar의 ApplicationContextFactory 항목이 없으면 AnnotationConfigApplicationContext 폴백 발생
    append("META-INF/spring.factories")
    append("META-INF/spring.handlers")
    append("META-INF/spring.schemas")
    append("META-INF/spring.tooling")
    append("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
    append("META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports")
    isZip64 = true

    // 불필요한 파일 제외하여 JAR 크기 최소화
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/LICENSE*")
    exclude("META-INF/NOTICE*")
    exclude("META-INF/DEPENDENCIES")
    exclude("META-INF/maven/**")
    exclude("META-INF/proguard/**")
    exclude("**/*.proto")
    exclude("google/protobuf/**")
    exclude("google/type/**")
    exclude("google/api/**")
    // Swagger UI 정적 에셋 (API 스펙만 필요, UI 파일 불필요)
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

    // AWS Lambda
    implementation("com.amazonaws:aws-lambda-java-core:1.2.3")
    // Lambda handler에서 MockMvc로 Spring DispatcherServlet 직접 호출 (Tomcat 소켓 불필요)
    implementation("org.springframework:spring-test")

    // SnapStart 체크포인트를 위해 crac 필수
    // crac가 없으면 열린 소켓(Tomcat, HikariCP)이 있어서 체크포인트 자체가 State:Failed
    // restore 후 Tomcat이 rebind 못해도 MockMvc는 소켓 없이 DispatcherServlet 직접 호출하므로 무관
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
