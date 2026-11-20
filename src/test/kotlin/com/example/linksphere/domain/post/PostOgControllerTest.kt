package com.example.linksphere.domain.post

import com.example.linksphere.domain.auth.MemberSessionService
import com.example.linksphere.global.config.SecurityConfig
import com.example.linksphere.global.config.security.CustomAccessDeniedHandler
import com.example.linksphere.global.config.security.CustomAuthenticationEntryPoint
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * 미리보기 봇이 받는 OG HTML을 실제 SecurityConfig로 본다(docs/plans/2026-11-19-post-link-in-app-and-og.md Phase 2-1).
 * 봇은 토큰을 보내지 않으니 비로그인으로 200이어야 하고, 기본 비헤이비어의 CloudFront 캐시가 5분 저장하도록 Cache-Control이
 * public이어야 한다. 한글 제목이 깨지지 않게 charset도 본다.
 */
@WebMvcTest(controllers = [PostOgController::class])
@Import(SecurityConfig::class, CustomAuthenticationEntryPoint::class, CustomAccessDeniedHandler::class)
class PostOgControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var postOgService: PostOgService

    @MockitoBean private lateinit var memberSessionService: MemberSessionService

    private val postId = UUID.randomUUID().toString()

    @Test
    fun `토큰 없이 요청해도 200 UTF-8 HTML을 5분 공개 캐시로 준다`() {
        `when`(postOgService.renderPostHtml(postId)).thenReturn("<title>한글 제목</title>")

        mockMvc.perform(get("/og/post/$postId"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
            .andExpect(header().doesNotExist(HttpHeaders.PRAGMA))
            .andExpect(content().string("<title>한글 제목</title>"))
    }

    // 봇이 Accept를 보내지 않거나 */*로 보내도 HTML을 받아야 한다
    @Test
    fun `Accept가 아무것이나 받는다고 해도 HTML로 준다`() {
        `when`(postOgService.renderPostHtml(postId)).thenReturn("<title>t</title>")

        mockMvc.perform(get("/og/post/$postId").header(HttpHeaders.ACCEPT, "*/*"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8"))
    }

    // 봇마다 보내는 Accept를 다 알 수 없다 - HTML을 받는다고 밝히지 않은 봇에게 406을 주면 미리보기가 아예 안 나온다.
    // serving-path 비교 도구(Accept: application/json)에서 406으로 실패해 찾았다
    @Test
    fun `Accept에 HTML이 없어도 406이 아니라 HTML로 준다`() {
        `when`(postOgService.renderPostHtml(postId)).thenReturn("<title>t</title>")

        mockMvc.perform(get("/og/post/$postId").header(HttpHeaders.ACCEPT, "application/json"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8"))
            .andExpect(content().string("<title>t</title>"))
    }
}
