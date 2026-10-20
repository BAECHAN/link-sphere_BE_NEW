package com.example.linksphere.global.config

import com.example.linksphere.domain.auth.SessionAuthenticationFilter
import com.example.linksphere.global.config.security.CustomAccessDeniedHandler
import com.example.linksphere.global.config.security.CustomAuthenticationEntryPoint
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val sessionAuthenticationFilter: SessionAuthenticationFilter,
    private val customAuthenticationEntryPoint: CustomAuthenticationEntryPoint,
    private val customAccessDeniedHandler: CustomAccessDeniedHandler,
    private val environment: Environment,
) {

    @Bean
    fun passwordEncoder(): org.springframework.security.crypto.password.PasswordEncoder = org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(
                    "/auth/signup",
                    "/auth/login",
                    "/auth/refresh",
                    "/auth/logout",
                    "/auth/email-availability",
                    "/auth/account/nickname-availability", // 가입 화면(비로그인)도 조회 가능 - 마이페이지와 겸용
                    "/auth/password-reset/request",
                    "/auth/password-reset/confirm",
                    "/auth/email-verification/request", // 로그인 여부와 무관하게 이메일만으로 재발송 가능
                    "/auth/email-verification/confirm",
                    "/common/**",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/error",
                    "/actuator/**",
                )
                    .permitAll()
                // 누가 보느냐에 따라 응답이 달라지는 공개 조회 - 토큰 없이 오면 비로그인으로 허용하되, 토큰을 냈는데
                // 만료·무효면(SessionAuthenticationFilter가 exception 속성을 남김) 거부해 진입점이 401을 쓰게 한다.
                // permitAll이면 만료 토큰이 조용히 비로그인으로 처리돼 FE가 갱신하지 못하고, 본인 비공개 글이 빠진
                // 200을 그대로 그린다(docs/plans/2026-10-20-public-get-expired-token-401.md). 만료 토큰을 일부러
                // 받는 /auth/refresh(위 목록)에는 걸지 않는다.
                it.requestMatchers(
                    HttpMethod.GET,
                    "/post",
                    "/post/*",
                    "/post/*/comment",
                )
                    .access { _, context -> AuthorizationDecision(context.request.getAttribute("exception") == null) }
                it.anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint(customAuthenticationEntryPoint)
                it.accessDeniedHandler(customAccessDeniedHandler)
            }
            .headers { headers ->
                // HSTS 자체는 Spring Security 기본 헤더에 이미 포함되지만 요청이 isSecure()일
                // 때만 실린다 - Lambda(MockMvc 재생) 경로의 secure(true) 보정과 짝이다
                // (LambdaHandler.kt 참고). Referrer-Policy는 기본 헤더가 아니라 명시가 필요하다.
                headers.httpStrictTransportSecurity {
                    it.maxAgeInSeconds(31536000).includeSubDomains(true)
                }
                headers.referrerPolicy {
                    it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)
                }
            }
            .addFilterBefore(
                sessionAuthenticationFilter,
                UsernamePasswordAuthenticationFilter::class.java,
            )

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()

        val allowedOrigins =
            Binder.get(environment)
                .bind("app.cors.allowed-origins", Bindable.listOf(String::class.java))
                .orElse(listOf())

        configuration.allowedOriginPatterns = allowedOrigins
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}
