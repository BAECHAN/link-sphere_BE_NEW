package com.example.linksphere.global.config

import com.example.linksphere.domain.auth.jwt.JwtAuthenticationFilter
import com.example.linksphere.global.config.security.CustomAccessDeniedHandler
import com.example.linksphere.global.config.security.CustomAuthenticationEntryPoint
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
        private val jwtAuthenticationFilter: JwtAuthenticationFilter,
        private val customAuthenticationEntryPoint: CustomAuthenticationEntryPoint,
        private val customAccessDeniedHandler: CustomAccessDeniedHandler,
        private val environment: Environment
) {

    @Bean
    fun passwordEncoder(): org.springframework.security.crypto.password.PasswordEncoder {
        return org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
    }

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
                                    "/common/**",
                                    "/swagger-ui/**",
                                    "/v3/api-docs/**",
                                    "/sse/debug/**",
                                    "/error",
                                    "/actuator/**",
                            )
                            .permitAll()
                    it.anyRequest().authenticated()
                }
                .exceptionHandling {
                    it.authenticationEntryPoint(customAuthenticationEntryPoint)
                    it.accessDeniedHandler(customAccessDeniedHandler)
                }
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter::class.java
                )

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        
        val allowedOrigins = Binder.get(environment)
                .bind("app.cors.allowed-origins", Bindable.listOf(String::class.java))
                .orElse(listOf())

        configuration.allowedOrigins = allowedOrigins
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}
