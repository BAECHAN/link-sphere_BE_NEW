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
                                .description("API documentation for Link Sphere application")
                                .version("1.0.0")
                )
                .addSecurityItem(SecurityRequirement().addList(securitySchemeName))
                .components(
                        Components()
                                .addSecuritySchemes(
                                        securitySchemeName,
                                        SecurityScheme()
                                                .name(securitySchemeName)
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                )
                )
    }
}
