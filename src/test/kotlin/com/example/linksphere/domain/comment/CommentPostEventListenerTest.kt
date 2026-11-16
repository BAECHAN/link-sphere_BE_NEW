package com.example.linksphere.domain.comment

import com.example.linksphere.domain.post.PostDeletingEvent
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.util.UUID

// 트랜잭션 표시(MANDATORY)는 프록시가 있어야 동작하므로 여기서는 보지 않는다 - 글 삭제가 실제로 이 리스너를 거쳐 댓글 이미지를
// 정리하는지는 PostDeleteCommentImageIntegrationTest가 본다.
class CommentPostEventListenerTest {

    @Test
    fun `글이 지워지기 직전에 그 글의 댓글 이미지를 정리한다`() {
        val postId = UUID.randomUUID()
        val commentService = mock(CommentService::class.java)

        CommentPostEventListener(commentService).onDeleting(PostDeletingEvent(postId))

        verify(commentService).deleteImagesForPost(postId)
    }
}
