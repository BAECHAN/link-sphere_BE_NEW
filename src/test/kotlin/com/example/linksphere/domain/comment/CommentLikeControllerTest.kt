package com.example.linksphere.domain.comment

import com.example.linksphere.domain.auth.MemberSessionService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(
    controllers = [CommentLikeController::class],
    excludeFilters =
    [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [SecurityFilterChain::class],
        ),
    ],
)
class CommentLikeControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var commentService: CommentService

    @MockitoBean private lateinit var memberSessionService: MemberSessionService

    @Test
    @WithMockUser(username = USER_ID)
    fun `PUT·DELETE 댓글 좋아요는 setCommentLike 결과를 isLiked 로 돌려준다`() {
        val commentId = UUID.randomUUID()
        `when`(commentService.setCommentLike(commentId, UUID.fromString(USER_ID), true)).thenReturn(true)
        `when`(commentService.setCommentLike(commentId, UUID.fromString(USER_ID), false)).thenReturn(false)

        mockMvc.perform(put("/comment/$commentId/like").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.isLiked").value(true))
        mockMvc.perform(delete("/comment/$commentId/like").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.isLiked").value(false))
    }

    companion object {
        private const val USER_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    }
}
