package com.example.linksphere.domain.comment

import com.example.linksphere.domain.post.UrlMetadataExtractor
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.global.exception.DuplicateCommentException
import com.example.linksphere.infra.aws.CommentJobDispatcher
import com.example.linksphere.infra.fcm.FcmNotificationService
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 댓글·답글 등록의 중복 방지를 실제 Postgres로 본다(docs/plans/2026-11-04-idempotent-create.md). 겹친 요청은 다른 트랜잭션이
// 같은 키를 INSERT하고 커밋을 늦춰 재현한다. 뒤 요청은 사전 조회를 통과한 뒤 유니크 인덱스에서 기다렸다가 위반으로 끝난다.
// 커밋이 겹쳐야 하므로 테스트 트랜잭션 밖에서 돌고 끝나면 지운다.
@PostgresIntegrationTest
@Import(CommentService::class, CommentPostProcessService::class, AdvisoryLock::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CommentIdempotencyIntegrationTest {

    @Autowired private lateinit var commentService: CommentService

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var supabaseStorageService: SupabaseStorageService

    @MockitoBean private lateinit var commentJobDispatcher: CommentJobDispatcher

    @MockitoBean private lateinit var fcmNotificationService: FcmNotificationService

    @MockitoBean private lateinit var urlMetadataExtractor: UrlMetadataExtractor

    private val userId = UUID.randomUUID()
    private val otherUserId = UUID.randomUUID()
    private val postId = UUID.randomUUID()
    private val otherPostId = UUID.randomUUID()
    private val executor = Executors.newFixedThreadPool(1)

    private val controller by lazy { CommentController(commentService) }

    @BeforeEach
    fun seed() {
        listOf(userId, otherUserId).forEach {
            jdbc.update("INSERT INTO members (id, email, nickname, password) VALUES (?, ?, ?, 'enc')", it, "$it@example.com", it.toString())
        }
        listOf(postId, otherPostId).forEach {
            jdbc.update("INSERT INTO posts (id, user_id, url, title) VALUES (?, ?, 'https://example.com', 't')", it, userId)
        }
    }

    @AfterEach
    fun cleanUp() {
        executor.shutdownNow()
        // members를 지우면 posts → comments가 FK cascade로 함께 지워진다
        jdbc.update("DELETE FROM members WHERE id IN (?, ?)", userId, otherUserId)
    }

    // 이미지가 있으면 잠금·시간 창을 건너뛰어 인덱스만 막고, 없으면 잠금을 잡은 뒤에도 커밋 전이라 행이 안 보여 역시 인덱스가 막는다
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `같은 키로 겹친 댓글은 유니크 위반 뒤 이긴 쪽 댓글을 돌려주고 후처리를 부르지 않는다`(withImage: Boolean) {
        val key = UUID.randomUUID()
        val images = if (withImage) listOf("https://cdn.example.com/a.png") else null

        val (winnerId, loserId) = overlapWithCommittedRow(key, parentId = null) {
            controller.createComment(postId, key, CreateCommentRequest(content = "내용", images = images), authOf(userId)).data.id
        }

        assertEquals(winnerId, loserId)
        assertEquals(1, countComments())
        verify(commentJobDispatcher, never()).dispatch(anyNonNull())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `같은 키로 겹친 답글도 이긴 쪽 답글을 돌려주고 후처리를 부르지 않는다`(withImage: Boolean) {
        val parentId = createComment(UUID.randomUUID(), "부모")
        val key = UUID.randomUUID()
        val images = if (withImage) listOf("https://cdn.example.com/a.png") else null

        val (winnerId, loserId) = overlapWithCommittedRow(key, parentId) {
            controller.createReply(parentId, key, CreateCommentRequest(content = "내용", images = images), authOf(userId)).data.id
        }

        assertEquals(winnerId, loserId)
        assertEquals(2, countComments())
        // 부모 댓글을 만들 때의 1회뿐이다
        verify(commentJobDispatcher, times(1)).dispatch(anyNonNull())
    }

    // 앞 요청이 잠금을 쥔 채 커밋한다. 뒤 요청이 잠금 뒤에 키를 다시 보지 않으면 시간 창에 걸려 409가 된다.
    @Test
    fun `같은 키로 잠금을 기다린 댓글은 시간 창이 아니라 앞 요청의 결과를 받는다`() {
        val key = UUID.randomUUID()

        val (winnerId, loserId) = overlapWithCommittedRow(key, parentId = null, lockKey = "comment-create:$userId:$postId:내용") {
            createComment(key, "내용")
        }

        assertEquals(winnerId, loserId)
        assertEquals(1, countComments())
        verify(commentJobDispatcher, never()).dispatch(anyNonNull())
    }

    @Test
    fun `같은 키로 잠금을 기다린 답글은 시간 창이 아니라 앞 요청의 결과를 받는다`() {
        val parentId = createComment(UUID.randomUUID(), "부모")
        val key = UUID.randomUUID()

        val (winnerId, loserId) = overlapWithCommittedRow(key, parentId, lockKey = "reply-create:$userId:$parentId:내용") {
            controller.createReply(parentId, key, CreateCommentRequest(content = "내용"), authOf(userId)).data.id
        }

        assertEquals(winnerId, loserId)
        assertEquals(2, countComments())
    }

    @Test
    fun `완료 뒤 재전송은 후처리 없이 처음 댓글을 돌려준다`() {
        val key = UUID.randomUUID()
        val firstId = createComment(key, "내용")

        val resentId = createComment(key, "내용")

        assertEquals(firstId, resentId)
        assertEquals(1, countComments())
        verify(commentJobDispatcher, times(1)).dispatch(anyNonNull())
    }

    @Test
    fun `다른 회원의 같은 키는 별개의 댓글이다`() {
        val key = UUID.randomUUID()

        assertNotEquals(createComment(key, "내용"), createComment(key, "내용", member = otherUserId))
    }

    @Test
    fun `다른 키로 1분 안에 같은 대상에 같은 본문을 쓰면 막힌다`() {
        createComment(UUID.randomUUID(), "내용")

        assertThrows(DuplicateCommentException::class.java) { createComment(UUID.randomUUID(), "내용") }

        assertEquals(1, countComments())
        verify(commentJobDispatcher, times(1)).dispatch(anyNonNull())
    }

    @Test
    fun `1분이 지나면 같은 본문을 다시 쓸 수 있다`() {
        createComment(UUID.randomUUID(), "내용")
        jdbc.update("UPDATE comments SET created_at = created_at - interval '61 seconds' WHERE user_id = ?", userId)

        createComment(UUID.randomUUID(), "내용")

        assertEquals(2, countComments())
    }

    @Test
    fun `다른 글, 다른 회원, 다른 본문은 시간 창에 걸리지 않는다`() {
        createComment(UUID.randomUUID(), "내용")

        createComment(UUID.randomUUID(), "내용", target = otherPostId)
        createComment(UUID.randomUUID(), "내용", member = otherUserId)
        createComment(UUID.randomUUID(), "다른 내용")

        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE post_id IN (?, ?)", Int::class.java, postId, otherPostId))
    }

    @Test
    fun `이미지가 있는 댓글은 시간 창을 적용하지 않는다`() {
        val request = CreateCommentRequest(content = "내용", images = listOf("https://cdn.example.com/a.png"))

        controller.createComment(postId, UUID.randomUUID(), request, authOf(userId))
        controller.createComment(postId, UUID.randomUUID(), request, authOf(userId))

        assertEquals(2, countComments())
    }

    @Test
    fun `삭제된(톰스톤) 댓글은 비교에서 뺀다`() {
        val id = createComment(UUID.randomUUID(), "내용")
        jdbc.update("UPDATE comments SET is_deleted = true WHERE id = ?", id)

        createComment(UUID.randomUUID(), "내용")

        assertEquals(2, countComments())
    }

    @Test
    fun `답글은 같은 부모에 같은 본문이면 막히고 다른 부모나 루트 댓글과는 겹치지 않는다`() {
        val parentId = createComment(UUID.randomUUID(), "부모")
        val otherParentId = createComment(UUID.randomUUID(), "다른 부모")
        createReply(parentId, "내용")

        assertThrows(DuplicateCommentException::class.java) { createReply(parentId, "내용") }

        createReply(otherParentId, "내용")
        createComment(UUID.randomUUID(), "내용")
        assertEquals(5, countComments())
    }

    @Test
    fun `댓글을 수정해도 키는 그대로다`() {
        val key = UUID.randomUUID()
        val id = createComment(key, "내용")

        commentService.updateComment(id, userId, "수정한 내용", null)

        assertEquals(key, jdbc.queryForObject("SELECT idempotency_key FROM comments WHERE id = ?", UUID::class.java, id))
    }

    /**
     * 다른 트랜잭션이 같은 키의 행을 먼저 INSERT하고 커밋을 늦추는 동안 [loser]를 돌린다. 이긴 행의 id와 [loser]의 결과를 돌려준다.
     * [lockKey]를 주면 서비스와 같은 advisory lock을 쥔 채로 INSERT한다.
     */
    private fun overlapWithCommittedRow(key: UUID, parentId: UUID?, lockKey: String? = null, loser: () -> UUID): Pair<UUID, UUID> {
        val winnerId = UUID.randomUUID()
        val inserted = CountDownLatch(1)
        val winner = executor.submit {
            TransactionTemplate(transactionManager).executeWithoutResult {
                if (lockKey != null) {
                    jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))", Int::class.java, lockKey)
                }

                jdbc.update(
                    "INSERT INTO comments (id, post_id, user_id, parent_id, content, idempotency_key) VALUES (?, ?, ?, ?, '내용', ?)",
                    winnerId,
                    postId,
                    userId,
                    parentId,
                    key,
                )
                inserted.countDown()
                Thread.sleep(500)
            }
        }
        inserted.await(10, TimeUnit.SECONDS)

        val loserId = loser()
        winner.get(15, TimeUnit.SECONDS)

        return winnerId to loserId
    }

    private fun createComment(key: UUID, content: String, target: UUID = postId, member: UUID = userId): UUID = controller
        .createComment(target, key, CreateCommentRequest(content = content), authOf(member))
        .data
        .id

    private fun createReply(parentId: UUID, content: String): UUID = controller
        .createReply(parentId, UUID.randomUUID(), CreateCommentRequest(content = content), authOf(userId))
        .data
        .id

    private fun authOf(member: UUID) = UsernamePasswordAuthenticationToken(member.toString(), null)

    private fun countComments(): Int = jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE post_id = ?", Int::class.java, postId)!!

    private fun <T> anyNonNull(): T {
        ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }
}
