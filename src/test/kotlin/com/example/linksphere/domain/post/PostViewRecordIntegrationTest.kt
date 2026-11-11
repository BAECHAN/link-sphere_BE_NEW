package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.comment.CommentService
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.infra.ai.GeminiService
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 조회 기록의 중복 제외를 실제 Postgres로 본다(docs/plans/2026-11-11-post-view-dedupe.md). 조회자 판단은 RateLimitService가
// 별도 트랜잭션(REQUIRES_NEW)으로 커밋하는 upsert라 mock으로는 "두 번째는 안 센다"가 재현되지 않는다. 그 행은 테스트
// 롤백에 걸리지 않으므로 운영처럼 요청마다 커밋되게 테스트 트랜잭션 밖에서 돌고 끝나면 지운다.
@PostgresIntegrationTest
@Import(PostService::class, RateLimitService::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostViewRecordIntegrationTest {

    @Autowired private lateinit var postService: PostService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var categoryService: CategoryService

    @MockitoBean private lateinit var commentService: CommentService

    @MockitoBean private lateinit var postResponseAssembler: PostResponseAssembler

    @MockitoBean private lateinit var urlMetadataExtractor: UrlMetadataExtractor

    @MockitoBean private lateinit var safeUrlValidator: SafeUrlValidator

    @MockitoBean private lateinit var geminiService: GeminiService

    @MockitoBean private lateinit var linkPreviewService: LinkPreviewService

    @MockitoBean private lateinit var advisoryLock: AdvisoryLock

    private val owner = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val postId = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        listOf(owner, viewer).forEach { jdbc.update("INSERT INTO members (id) VALUES (?)", it) }
        jdbc.update("INSERT INTO posts (id, user_id, url, title, is_private) VALUES (?, ?, 'https://example.com', 't', false)", postId, owner)
    }

    @AfterEach
    fun cleanUp() {
        jdbc.update("DELETE FROM auth_rate_limits WHERE bucket_key LIKE ?", "post-view:$postId:%")
        jdbc.update("DELETE FROM post_views WHERE post_id = ?", postId)
        jdbc.update("DELETE FROM posts WHERE id = ?", postId)
        jdbc.update("DELETE FROM members WHERE id IN (?, ?)", owner, viewer)
    }

    @Test
    fun `같은 회원이 다시 열면 조회수는 그대로이고 최근 열람 시각은 갱신된다`() {
        assertEquals(1, postService.recordView(postId, viewer, null).viewCount)
        jdbc.update("UPDATE post_views SET viewed_at = '2000-01-01' WHERE user_id = ? AND post_id = ?", viewer, postId)

        assertEquals(1, postService.recordView(postId, viewer, null).viewCount)

        assertEquals(1, viewCountInDb())
        assertEquals(true, viewedAfter2001(viewer))
    }

    @Test
    fun `다른 회원이 열면 조회수가 오른다`() {
        postService.recordView(postId, viewer, null)

        assertEquals(2, postService.recordView(postId, owner, null).viewCount)
    }

    @Test
    fun `비로그인은 같은 IP면 한 번만 세고 다른 IP면 따로 센다`() {
        assertEquals(1, postService.recordView(postId, null, "203.0.113.7").viewCount)
        assertEquals(1, postService.recordView(postId, null, "203.0.113.7").viewCount)

        assertEquals(2, postService.recordView(postId, null, "203.0.113.8").viewCount)
    }

    // 로그인하면 IP가 같아도 회원으로 가린다 - 비로그인으로 본 뒤 로그인해 다시 보면 한 번 더 센다
    @Test
    fun `회원은 같은 IP의 비로그인 조회와 따로 센다`() {
        postService.recordView(postId, null, "203.0.113.7")

        assertEquals(2, postService.recordView(postId, viewer, "203.0.113.7").viewCount)
    }

    @Test
    fun `IP를 모르는 비로그인 조회는 매번 센다`() {
        postService.recordView(postId, null, null)

        assertEquals(2, postService.recordView(postId, null, null).viewCount)
    }

    private fun viewCountInDb(): Int = jdbc.queryForObject("SELECT COALESCE(view_count, 0) FROM posts WHERE id = ?", Int::class.java, postId)!!

    private fun viewedAfter2001(userId: UUID): Boolean = jdbc.queryForObject(
        "SELECT viewed_at > '2001-01-01' FROM post_views WHERE user_id = ? AND post_id = ?",
        Boolean::class.java,
        userId,
        postId,
    )!!
}
