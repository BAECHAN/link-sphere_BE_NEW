package com.example.linksphere.global.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SwaggerConfig {

    @Bean
    fun openAPI(): OpenAPI {
        val securitySchemeName = "bearerAuth"
        return OpenAPI()
            .info(
                Info().title("Link Sphere API")
                    .description(
                        "로컬 개발용 API 문서. 운영 Lambda 에는 Swagger UI 가 포함되지 않는다(스펙 JSON 만).\n\n" +
                            "- 성공 응답은 모두 `ApiResponse<T>`(status·message·data·timestamp)로 감싼다.\n" +
                            "- 실패 응답은 모두 `ErrorResponse`(status·code·message·timestamp)다. " +
                            "code 는 대문자 SNAKE_CASE(예: POST_NOT_FOUND).\n" +
                            "- 자물쇠가 붙은 API 는 Authorization: Bearer <accessToken> 이 필요하다. " +
                            "누락·만료·위조 시 401(NOT_LOGGED_IN / TOKEN_EXPIRED / INVALID_TOKEN)이다.\n" +
                            "- 처리되지 않은 예외는 500 INTERNAL_SERVER_ERROR 로 통일된다.\n" +
                            "- 성공 응답의 HTTP 상태는 본문 status 필드와 같다 - 자원을 만드는 POST 는 201, 그 밖은 200 이다.",
                    )
                    .version("1.0.0"),
            )
            .addSecurityItem(SecurityRequirement().addList(securitySchemeName))
            .components(
                Components()
                    .addSecuritySchemes(
                        securitySchemeName,
                        // bearerFormat은 지정하지 않는다 - PR #42(JWT 폐지)로 토큰이
                        // SecureToken.generate()가 만드는 불투명 문자열이 돼, "JWT"라고
                        // 표시하면 스펙을 읽는 사람에게 형식을 잘못 알리게 된다.
                        SecurityScheme()
                            .name(securitySchemeName)
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer"),
                    ),
            )
    }
}
