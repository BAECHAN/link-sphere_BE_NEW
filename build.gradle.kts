plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.4.21"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
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

    // 패키징 대상은 runtimeClasspath(기본값)가 아니라 productionRuntimeClasspath 다.
    // Spring Boot 플러그인이 runtimeClasspath.extendsFrom(developmentOnly) 를 걸어두기 때문에
    // 기본값을 쓰면 developmentOnly 의존성(Swagger UI)이 Lambda fat JAR 에 그대로 딸려 들어간다.
    configurations = listOf<org.gradle.api.file.FileCollection>(
        project.configurations.getByName("productionRuntimeClasspath"),
    )

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
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    runtimeOnly("org.postgresql:postgresql")

    // posts.embedding(pgvector) 컬럼을 FloatArray로 매핑. Boot BOM이 hibernate-vector는 관리하지 않아 버전을 직접
    // 적어야 하는데, 숫자로 고정하면 Boot를 올릴 때 hibernate-core와 어긋난다(3.5.8 → 3.5.16에서 6.6.36 → 6.6.53).
    // 그래서 BOM의 hibernate.version을 그대로 읽는다 - dependency-management 플러그인 문서 §5
    // https://docs.spring.io/dependency-management-plugin/docs/1.1.7/reference/html/#accessing-properties
    implementation("org.hibernate.orm:hibernate-vector:${dependencyManagement.importedProperties["hibernate.version"]}")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // 아키텍처 규칙을 컴파일된 바이트코드로 검증 - DB·Spring 컨텍스트 없이 수십 ms 안에 끝난다.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")

    // 저장소 쿼리를 실제 Postgres(pgvector)로 검증하는 통합 테스트 - @Tag("integration")만 integrationTest
    // 태스크에서 돈다. Docker가 필요해 기본 test에서는 빠진다(docs/plans/2026-10-14-integration-test.md).
    // 버전은 Spring Boot BOM이 관리한다.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")

    // SpringDoc OpenAPI (Swagger) 라이브러리 추가 — UI 에셋 제외한 API 스펙만 포함
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.10.0")

    // Swagger UI 는 로컬 bootRun 전용이다. developmentOnly 는 shadowJar 가 쓰는
    // productionRuntimeClasspath 에 들어가지 않으므로 Lambda 아티팩트는 그대로다.
    // 로컬: http://localhost:8080/api/swagger-ui/index.html
    developmentOnly("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.10.0")

    // Jsoup
    implementation("org.jsoup:jsoup:1.23.2")

    // Firebase Admin SDK (FCM 푸시 알림) — FCM만 사용하므로 불필요한 GCP 모듈 제외
    implementation("com.google.firebase:firebase-admin:9.11.0") {
        exclude(group = "com.google.cloud", module = "google-cloud-firestore")
        exclude(group = "com.google.cloud", module = "google-cloud-storage")
        exclude(group = "com.google.cloud", module = "google-cloud-bigquery")
    }
    // firebase-admin이 의존하는 JacksonFactory가 classpath에서 누락되는 문제 해결
    implementation("com.google.http-client:google-http-client-jackson2:2.2.0")

    // Lambda RequestStreamHandler 인터페이스
    implementation("com.amazonaws:aws-lambda-java-core:1.4.1")

    // MockMvc로 DispatcherServlet을 직접 호출하기 위해 필요 (Tomcat 소켓 불사용)
    implementation("org.springframework:spring-test")

    // SnapStart CRaC 지원: 체크포인트 전 Tomcat/HikariCP 소켓을 자동으로 닫고 복원 후 재연결
    // 이 의존성 없이는 열린 소켓 때문에 SnapStart 체크포인트가 State:Failed가 된다
    implementation("org.crac:crac")

    // AI 분석을 별도 Lambda 호출로 위임(self-invoke)하기 위한 클라이언트.
    // url-connection-client는 커넥션 풀을 유지하지 않아(요청마다 새로 열고 닫음) SnapStart
    // 체크포인트 시점에 열린 소켓이 남지 않는다 — Apache/Netty 클라이언트는 풀링 때문에 위험하다.
    implementation(platform("software.amazon.awssdk:bom:2.57.4"))
    implementation("software.amazon.awssdk:lambda") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")

    // 비밀번호 찾기·이메일 인증 메일 발송(MailService). 위 lambda 클라이언트와 같은 이유로
    // netty/apache 대신 url-connection-client를 쓴다.
    implementation("software.amazon.awssdk:ses") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
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

// 통합 테스트(@Tag("integration"))는 Docker가 필요해 기본 test에서 빼고 integrationTest로만 돌린다.
// 로컬·배포의 test는 지금처럼 Docker 없이 돌고, CI check 잡이 둘 다 돌린다.
// 스펙 내보내기(@Tag("openapi"))도 Docker가 필요하고 검증이 아니라 산출물을 만드는 일이라 openApiSpec으로만 돌린다.
tasks.test {
    useJUnitPlatform { excludeTags("integration", "openapi") }
}

val integrationTest by tasks.registering(Test::class) {
    description = "실제 Postgres 컨테이너로 저장소 쿼리를 검증한다(Docker 필요)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}

// PR CI(api-compat.yml)가 운영 스펙과 비교할 현재 코드의 OpenAPI 스펙을 build/openapi/openapi.json으로 남긴다(Docker 필요).
// 코드가 그대로여도 매번 새로 만든다 - 비교 대상인 운영 스펙이 바뀌므로 Gradle의 up-to-date 판정에 맡기지 않는다.
val openApiSpec by tasks.registering(Test::class) {
    description = "현재 코드의 OpenAPI 스펙을 build/openapi/openapi.json으로 내보낸다(Docker 필요)."
    group = "documentation"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("openapi") }
    systemProperty("openapi.outputFile", layout.buildDirectory.file("openapi/openapi.json").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}

// 공개 API 문서(api-docs-pages.yml → GitHub Pages)에 올릴 Swagger UI 정적 파일을 build/api-docs-site/에 모은다.
// 파일은 springdoc-openapi-starter-webmvc-ui가 끌어오는 swagger-ui webjar(developmentOnly → runtimeClasspath)에서 꺼내므로
// springdoc을 올리면 함께 올라간다. webjar 안 경로(META-INF/resources/webjars/swagger-ui/<버전>/)는 떼고, 기본
// swagger-initializer.js(petstore 예제 스펙을 부른다)는 빼고 api-docs-site/의 설정으로 바꿔 넣는다. 스펙(openapi.json)은
// 워크플로가 openApiSpec 결과로 만들어 넣는다(docs/plans/2026-10-30-docs-to-github-pages.md).
val swaggerUiSite by tasks.registering(Sync::class) {
    description = "Swagger UI 정적 파일을 build/api-docs-site/로 모은다(공개 API 문서용)."
    group = "documentation"
    from(provider { configurations.runtimeClasspath.get().filter { it.name.startsWith("swagger-ui-") }.map { zipTree(it) } }) {
        include("META-INF/resources/webjars/swagger-ui/*/**")
        exclude("**/*.map", "**/oauth2-redirect.*", "**/swagger-initializer.js")
        eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(5).toTypedArray()) }
        includeEmptyDirs = false
    }
    from("api-docs-site")
    into(layout.buildDirectory.dir("api-docs-site"))
}

// ktlint는 Kotlin 컴파일러(kotlin-compiler-embeddable)를 품고 도는데, Spring Boot 플러그인이 kotlin.version을 Kotlin 플러그인
// 버전으로 잡고 dependency-management가 그 값을 모든 configuration에 강제해 ktlint의 컴파일러까지 바뀐다. ktlint 1.8.0 이하
// (ktlint-gradle 14.2.0 기본값 1.5.0)는 Kotlin 2.4 컴파일러로 초기화하지 못해 "Extensions storage is not registered"로 실패한다
// (Kotlin 2.1.0 → 2.4.21로 올린 Dependabot #118에서 재현) - https://github.com/ktlint/ktlint/issues/3403
// 그래서 ktlint configuration에서만 org.jetbrains.kotlin을 ktlint가 요청한 버전으로 되돌린다. 숫자로 고정하지 않아야 ktlint를
// 올렸을 때 컴파일러도 그 ktlint가 요청하는 버전을 따라간다.
// eachDependency 규칙은 선언 순서대로 평가돼 마지막 useVersion이 이기고, requested는 앞 규칙이 바꿔도 원래 값 그대로다.
// dependency-management의 규칙은 플러그인 적용 때(ktlint configuration이 생길 때) 먼저 등록되므로 이 블록은 그 뒤인 스크립트
// 본문에 있어야 이긴다 - https://docs.gradle.org/8.14.3/javadoc/org/gradle/api/artifacts/ResolutionStrategy.html
// ktlint-gradle이 Kotlin 2.4를 지원하는 ktlint 2.x를 쓸 수 있게 되면 이 블록이 아직 필요한지 다시 본다.
configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(requested.version!!)
        }
    }
}
