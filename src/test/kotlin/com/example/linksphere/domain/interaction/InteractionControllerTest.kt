package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.auth.MemberSessionService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(
    controllers = [InteractionController::class],
    excludeFilters =
    [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [SecurityFilterChain::class],
        ),
    ],
)
class InteractionControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var interactionService: InteractionService

    @MockitoBean private lateinit var bookmarkFolderService: BookmarkFolderService

    @MockitoBean private lateinit var memberSessionService: MemberSessionService

    @Test
    @WithMockUser(username = USER_ID)
    fun `좋아요 처리 중 동시 요청과 부딪혀 낙관적 락 예외가 나면 500 이 아니라 409 CONCURRENT_UPDATE 로 응답한다`() {
        // 2026-10-13 운영에서 같은 사용자의 동시 토글이 StaleObjectStateException → 500 으로 새던 경로.
        // 토글은 일괄 DELETE로 바꿔 더는 이 예외가 나지 않지만, 같은 종류의 예외가 남은 경로의 안전망을 고정한다.
        val postId = UUID.randomUUID()
        `when`(interactionService.togglePostLike(postId, UUID.fromString(USER_ID)))
            .thenThrow(ObjectOptimisticLockingFailureException(TablePostReaction::class.java, postId))

        mockMvc.perform(post("/post/$postId/like").with(csrf()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"))
    }

    companion object {
        private const val USER_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    }
}
