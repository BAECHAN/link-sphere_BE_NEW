package com.example.linksphere.domain.post

import com.example.linksphere.domain.auth.MemberSessionService
import com.example.linksphere.global.exception.RateLimitExceededException
import com.example.linksphere.global.ratelimit.RateLimitService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID

@WebMvcTest(
    controllers = [LinkPreviewController::class],
    excludeFilters =
    [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [SecurityFilterChain::class],
        ),
    ],
)
class LinkPreviewControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var postService: PostService

    @MockitoBean private lateinit var rateLimitService: RateLimitService

    @MockitoBean private lateinit var memberSessionService: MemberSessionService

    @Test
    @WithMockUser(username = MEMBER_ID)
    fun `previewLink는 회원별 한도를 먼저 센 뒤 서비스 결과를 200으로 돌려준다`() {
        val url = "https://example.com/preview"
        `when`(postService.previewLink(UUID.fromString(MEMBER_ID), url))
            .thenReturn(LinkPreviewResponse(url = url, title = "제목", description = "설명", ogImage = "https://example.com/og.png"))

        mockMvc.perform(get("/link-preview").param("url", url))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.url").value(url))
            .andExpect(jsonPath("$.data.title").value("제목"))
            .andExpect(jsonPath("$.data.description").value("설명"))
            .andExpect(jsonPath("$.data.ogImage").value("https://example.com/og.png"))

        val order = inOrder(rateLimitService, postService)
        order.verify(rateLimitService).consume("link-preview:member:$MEMBER_ID", 60, Duration.ofHours(1))
        order.verify(postService).previewLink(UUID.fromString(MEMBER_ID), url)
    }

    @Test
    @WithMockUser(username = MEMBER_ID)
    fun `previewLink는 한도를 넘으면 서비스를 부르지 않고 429 RATE_LIMIT_EXCEEDED를 준다`() {
        doThrow(RateLimitExceededException("Too many requests", retryAfterSeconds = 120))
            .`when`(rateLimitService).consume("link-preview:member:$MEMBER_ID", 60, Duration.ofHours(1))

        mockMvc.perform(get("/link-preview").param("url", "https://example.com/preview"))
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
            .andExpect(header().string("Retry-After", "120"))

        verifyNoInteractions(postService)
    }

    companion object {
        private const val MEMBER_ID = "11111111-1111-1111-1111-111111111111"
    }
}
