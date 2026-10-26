package com.example.linksphere.global.config

import com.example.linksphere.domain.auth.MemberSessionService
import com.example.linksphere.domain.category.CategoryController
import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.global.config.security.CustomAccessDeniedHandler
import com.example.linksphere.global.config.security.CustomAuthenticationEntryPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * 인증은 SessionAuthenticationFilter만 한다. Boot 기본 사용자(UserDetailsServiceAutoConfiguration의
 * InMemoryUserDetailsManager)는 oauth2-client가 클래스패스에 있는 동안 저절로 꺼져 있었는데, 그 의존성을 지우자 켜져
 * 개발용 비밀번호가 운영 로그에 찍혔다(2026-10-26). application.yml의 spring.autoconfigure.exclude로 끈다 - 슬라이스
 * 테스트도 이 속성으로 자동 설정을 빼므로, 실제 SecurityConfig를 띄운 이 슬라이스에서 빈이 없는지 본다.
 */
@WebMvcTest(controllers = [CategoryController::class])
@Import(SecurityConfig::class, CustomAuthenticationEntryPoint::class, CustomAccessDeniedHandler::class)
class SecurityAutoConfigurationTest {

    @Autowired private lateinit var applicationContext: ApplicationContext

    @MockitoBean private lateinit var categoryService: CategoryService

    @MockitoBean private lateinit var memberSessionService: MemberSessionService

    @Test
    fun `Boot 기본 사용자 UserDetailsService를 만들지 않는다`() {
        assertEquals(emptyList<String>(), applicationContext.getBeanNamesForType(UserDetailsService::class.java).toList())
    }
}
