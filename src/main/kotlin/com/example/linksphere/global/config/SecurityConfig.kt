package com.example.linksphere.global.config

import com.example.linksphere.domain.auth.jwt.JwtAuthenticationFilter
import com.example.linksphere.global.config.security.CustomAccessDeniedHandler
import com.example.linksphere.global.config.security.CustomAuthenticationEntryPoint
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
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
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
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
                    "/common/**",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/error",
                    "/actuator/**",
                )
                    .permitAll()
                it.requestMatchers(
                    HttpMethod.GET,
                    "/post",
                    "/post/*",
                    "/post/*/comment",
                )
                    .permitAll()
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
                jwtAuthenticationFilter,
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
