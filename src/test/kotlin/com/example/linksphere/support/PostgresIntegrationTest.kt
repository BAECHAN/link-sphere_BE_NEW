package com.example.linksphere.support

import org.junit.jupiter.api.Tag
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 저장소 쿼리를 운영과 같은 스키마의 실제 Postgres에서 검증하는 통합 테스트(docs/plans/2026-10-14-integration-test.md).
 *
 * - @DataJpaTest: JPA·저장소만 띄워 Supabase·Gemini 같은 외부 설정 없이 뜬다. 테스트마다 트랜잭션을 열고 롤백한다.
 * - 컨테이너는 @ServiceConnection으로 붙어 application-secret.yml의 운영 datasource보다 우선한다. Boot 3.4+의
 *   @DataJpaTest는 이 연결을 테스트 DB로 보고 내장 DB로 바꾸지 않는다.
 * - 스키마는 운영 덤프(src/test/resources/db/schema.sql)다.
 * - @Tag("integration")이라 ./gradlew test가 아니라 ./gradlew integrationTest에서만 돈다(Docker 필요).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@DataJpaTest
@Import(PostgresTestContainers::class)
@Tag("integration")
annotation class PostgresIntegrationTest

// 같은 설정을 쓰는 테스트 클래스끼리는 Spring 테스트 컨텍스트 캐시로 컨테이너 하나를 함께 쓴다
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestContainers {

    // 운영 서버가 PostgreSQL 17.6이고 posts.embedding이 pgvector라 pgvector 이미지의 17을 쓴다
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
        .withInitScript("db/schema.sql")
}
