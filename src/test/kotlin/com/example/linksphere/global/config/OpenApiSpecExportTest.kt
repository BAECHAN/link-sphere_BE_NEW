package com.example.linksphere.global.config

import com.example.linksphere.support.PostgresTestContainers
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files
import java.nio.file.Path

/**
 * 지금 코드가 내보내는 OpenAPI 스펙을 파일로 남긴다. PR CI(api-compat.yml)가 이 파일을 운영 스펙과 oasdiff로
 * 비교해 FE를 깨는 변경을 병합 전에 잡는다(docs/plans/2026-10-29-api-contract-checks.md).
 *
 * - 앱 전체를 띄워야 springdoc이 모든 컨트롤러를 읽는다. 운영 설정(application-secret.yml)은 CI에 없으므로
 *   @ActiveProfiles가 yml의 `secret` 프로필을 대신하고, 기본값 없는 외부 서비스 설정은 더미 값으로 채운다.
 *   스펙은 컨트롤러·DTO 시그니처로만 만들어져 이 값들이 결과에 들어가지 않는다.
 * - FCM 키 파일은 로컬에만 있어서 경로를 비워 로컬과 CI가 같은 상태(FCM 꺼짐)에서 스펙을 만든다.
 * - DB는 통합 테스트와 같은 pgvector 컨테이너를 쓴다. 그래서 @Tag("openapi")로 묶어 ./gradlew openApiSpec에서만
 *   돈다(Docker 필요, test·integrationTest에서는 빠진다).
 * - MockMvc는 context-path(/api) 없이 디스패처를 직접 부른다 - 운영 LambdaHandler도 같은 방식이다. 경로 차이는
 *   springdoc이 채우는 servers 필드에만 남고, CI가 비교 전에 지운다.
 */
@SpringBootTest(
    properties = [
        "gemini.api.key=dummy",
        "supabase.url=http://localhost",
        "supabase.key=dummy",
        "supabase.bucket=dummy",
        "firebase.service-account-key-path=",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("openapi-spec")
@Import(PostgresTestContainers::class)
@Tag("openapi")
class OpenApiSpecExportTest {

    @Autowired private lateinit var mockMvc: MockMvc

    // 스펙 생성이 조용히 빈 결과로 끝나는 것을 막는 하한선. 정확한 개수가 아니라 "생성이 동작했다"는 것만
    // 보증한다(2026-10 기준 37개).
    private val minimumPathCount = 30

    @Test
    fun `현재 코드의 OpenAPI 스펙을 파일로 남긴다`() {
        val body = mockMvc.get("/v3/api-docs")
            .andExpect { status { isOk() } }
            .andReturn()
            .response
            .getContentAsString(Charsets.UTF_8)

        val pathCount = ObjectMapper().readTree(body).path("paths").size()
        assertTrue(
            pathCount >= minimumPathCount,
            "스펙에서 경로를 최소 $minimumPathCount 개 찾아야 하는데 ${pathCount}개만 찾았다.",
        )

        val output = Path.of(System.getProperty("openapi.outputFile", "build/openapi/openapi.json"))
        Files.createDirectories(output.parent)
        Files.writeString(output, body)
    }
}
