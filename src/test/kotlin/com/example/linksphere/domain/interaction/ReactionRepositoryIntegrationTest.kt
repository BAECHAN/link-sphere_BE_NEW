package com.example.linksphere.domain.interaction

import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@PostgresIntegrationTest
class ReactionRepositoryIntegrationTest {

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Autowired private lateinit var postReactionRepository: PostReactionRepository

    @Autowired private lateinit var commentReactionRepository: CommentReactionRepository

    private val userId = UUID.randomUUID()
    private val postId = UUID.randomUUID()
    private val commentId = UUID.randomUUID()

    @Test
    fun `게시글·댓글 좋아요 insertIgnoreConflict 는 두 번 불러도 하나다`() {
        seed()

        assertEquals(1, postReactionRepository.insertIgnoreConflict(userId, postId))
        assertEquals(0, postReactionRepository.insertIgnoreConflict(userId, postId))
        assertEquals(1, commentReactionRepository.insertIgnoreConflict(userId, commentId))
        assertEquals(0, commentReactionRepository.insertIgnoreConflict(userId, commentId))

        assertEquals(1L, postReactionRepository.countByPostId(postId))
    }

    @Test
    fun `좋아요 일괄 DELETE 는 지울 행이 없어도 예외 없이 0을 돌려준다`() {
        seed()

        assertEquals(0, postReactionRepository.deleteByUserIdAndPostId(userId, postId))
        assertEquals(0, commentReactionRepository.deleteByUserIdAndCommentId(userId, commentId))
    }

    // 2026-10-13 좋아요 500(#79) 재현: 같은 좋아요를 두 요청이 동시에 지운다. 뒤의 DELETE는 앞 트랜잭션이 커밋할
    // 때까지 행 잠금에서 기다렸다가 0행으로 끝나야 한다 — 옛 derived delete(엔티티 로드 후 remove)는 여기서
    // StaleObjectStateException을 냈다. 실제로 두 커밋이 겹쳐야 하므로 테스트 트랜잭션 밖에서 돌고 끝나면 지운다.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `같은 좋아요를 두 트랜잭션이 동시에 지워도 예외 없이 한쪽만 1행을 지운다`() {
        val tx = TransactionTemplate(transactionManager)
        tx.executeWithoutResult {
            seed()
            postReactionRepository.insertIgnoreConflict(userId, postId)
        }
        val firstDeleted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<Int> {
                tx.execute {
                    val deleted = postReactionRepository.deleteByUserIdAndPostId(userId, postId)
                    firstDeleted.countDown()
                    // 두 번째 DELETE가 이 행 잠금에서 기다리는 동안 커밋을 늦춘다
                    Thread.sleep(500)
                    deleted
                }
            }
            val second = executor.submit<Int> {
                firstDeleted.await(10, TimeUnit.SECONDS)
                tx.execute { postReactionRepository.deleteByUserIdAndPostId(userId, postId) }
            }

            assertEquals(listOf(0, 1), listOf(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)).sorted())
            assertEquals(0L, postReactionRepository.countByPostId(postId))
        } finally {
            executor.shutdownNow()
            // members를 지우면 posts → post_reactions·comments가 FK cascade로 함께 지워진다
            jdbc.update("DELETE FROM members WHERE id = ?", userId)
        }
    }

    private fun seed() {
        jdbc.update("INSERT INTO members (id) VALUES (?)", userId)
        jdbc.update("INSERT INTO posts (id, user_id, url, title) VALUES (?, ?, 'https://example.com', 't')", postId, userId)
        jdbc.update("INSERT INTO comments (id, post_id, user_id, content) VALUES (?, ?, ?, 'c')", commentId, postId, userId)
    }
}
