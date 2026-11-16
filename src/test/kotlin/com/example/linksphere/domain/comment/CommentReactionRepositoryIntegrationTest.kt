package com.example.linksphere.domain.comment

import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

// 댓글 좋아요 저장소의 SQL을 실제 Postgres로 본다. interaction의 ReactionRepositoryIntegrationTest에 글 좋아요와 함께 있던 것을
// 댓글 좋아요가 comment로 옮겨 오며 나눴다(docs/plans/2026-11-16-be-structure-stage2.md PR-5).
@PostgresIntegrationTest
class CommentReactionRepositoryIntegrationTest {

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var commentReactionRepository: CommentReactionRepository

    private val userId = UUID.randomUUID()
    private val postId = UUID.randomUUID()
    private val commentId = UUID.randomUUID()

    @Test
    fun `댓글 좋아요 insertIgnoreConflict 는 두 번 불러도 하나다`() {
        seed()

        assertEquals(1, commentReactionRepository.insertIgnoreConflict(userId, commentId))
        assertEquals(0, commentReactionRepository.insertIgnoreConflict(userId, commentId))
    }

    @Test
    fun `댓글 좋아요 일괄 DELETE 는 지울 행이 없어도 예외 없이 0을 돌려준다`() {
        seed()

        assertEquals(0, commentReactionRepository.deleteByUserIdAndCommentId(userId, commentId))
    }

    // 좋아요가 없는 댓글은 결과에 없다(호출부가 0으로 채운다)
    @Test
    fun `GROUP BY 개수 쿼리는 행을 다 읽어 센 값과 같다`() {
        seed()
        val users = List(3) { UUID.randomUUID() }
        val comments = List(2) { UUID.randomUUID() }
        users.forEach { jdbc.update("INSERT INTO members (id) VALUES (?)", it) }
        comments.forEach { jdbc.update("INSERT INTO comments (id, post_id, user_id, content) VALUES (?, ?, ?, 'c')", it, postId, userId) }
        users.forEach { commentReactionRepository.insertIgnoreConflict(it, comments[0]) }

        val commentLikeCounts = commentReactionRepository.countByCommentIdIn(comments).associate { it.commentId to it.count.toInt() }

        assertEquals(commentReactionRepository.findAll().filter { it.commentId in comments }.groupingBy { it.commentId }.eachCount(), commentLikeCounts)
        assertEquals(mapOf(comments[0] to 3), commentLikeCounts)
    }

    private fun seed() {
        jdbc.update("INSERT INTO members (id) VALUES (?)", userId)
        jdbc.update("INSERT INTO posts (id, user_id, url, title) VALUES (?, ?, 'https://example.com', 't')", postId, userId)
        jdbc.update("INSERT INTO comments (id, post_id, user_id, content) VALUES (?, ?, ?, 'c')", commentId, postId, userId)
    }
}
