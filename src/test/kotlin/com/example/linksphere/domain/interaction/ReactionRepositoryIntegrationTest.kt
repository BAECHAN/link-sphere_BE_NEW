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

    @Autowired private lateinit var bookmarkRepository: BookmarkRepository

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

    // 목록 응답의 좋아요·북마크 수를 메모리 집계(행을 다 읽어 groupingBy.eachCount)에서 GROUP BY 쿼리로 바꿨다(2026-10-26).
    // 같은 픽스처에서 두 방식이 같은 값을 내는지 본다 - 반응이 없는 글·댓글은 결과에 없고(호출부가 0으로 채운다), 요청하지 않은
    // id는 세지 않는다.
    @Test
    fun `GROUP BY 개수 쿼리는 행을 다 읽어 센 값과 같다`() {
        val users = List(3) { UUID.randomUUID() }
        val posts = List(3) { UUID.randomUUID() }
        val comments = List(2) { UUID.randomUUID() }
        users.forEach { jdbc.update("INSERT INTO members (id) VALUES (?)", it) }
        posts.forEach { jdbc.update("INSERT INTO posts (id, user_id, url, title) VALUES (?, ?, 'https://example.com', 't')", it, users[0]) }
        comments.forEach { jdbc.update("INSERT INTO comments (id, post_id, user_id, content) VALUES (?, ?, ?, 'c')", it, posts[0], users[0]) }
        users.forEach { postReactionRepository.insertIgnoreConflict(it, posts[0]) }
        postReactionRepository.insertIgnoreConflict(users[1], posts[1])
        users.take(2).forEach { bookmarkRepository.insertIgnoreConflict(it, posts[0]) }
        bookmarkRepository.insertIgnoreConflict(users[2], posts[2])
        users.forEach { commentReactionRepository.insertIgnoreConflict(it, comments[0]) }
        val asked = posts.take(2)

        val likeCounts = postReactionRepository.countByPostIdIn(asked).associate { it.postId to it.count.toInt() }
        val bookmarkCounts = bookmarkRepository.countByPostIdIn(asked).associate { it.postId to it.count.toInt() }
        val commentLikeCounts = commentReactionRepository.countByCommentIdIn(comments).associate { it.commentId to it.count.toInt() }

        assertEquals(postReactionRepository.findAll().filter { it.postId in asked }.groupingBy { it.postId }.eachCount(), likeCounts)
        assertEquals(bookmarkRepository.findAll().filter { it.postId in asked }.groupingBy { it.postId }.eachCount(), bookmarkCounts)
        assertEquals(commentReactionRepository.findAll().filter { it.commentId in comments }.groupingBy { it.commentId }.eachCount(), commentLikeCounts)
        assertEquals(mapOf(posts[0] to 3, posts[1] to 1), likeCounts)
        assertEquals(mapOf(posts[0] to 2), bookmarkCounts)
        assertEquals(mapOf(comments[0] to 3), commentLikeCounts)
    }

    private fun seed() {
        jdbc.update("INSERT INTO members (id) VALUES (?)", userId)
        jdbc.update("INSERT INTO posts (id, user_id, url, title) VALUES (?, ?, 'https://example.com', 't')", postId, userId)
        jdbc.update("INSERT INTO comments (id, post_id, user_id, content) VALUES (?, ?, ?, 'c')", commentId, postId, userId)
    }
}
