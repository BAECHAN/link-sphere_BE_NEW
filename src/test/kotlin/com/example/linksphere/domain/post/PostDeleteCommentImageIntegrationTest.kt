package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.comment.CommentCountAdapter
import com.example.linksphere.domain.comment.CommentPostEventListener
import com.example.linksphere.domain.comment.CommentService
import com.example.linksphere.domain.interaction.PostInteractionAdapter
import com.example.linksphere.domain.linkpreview.LinkPreviewService
import com.example.linksphere.domain.linkpreview.SafeUrlValidator
import com.example.linksphere.domain.linkpreview.UrlMetadataExtractor
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.global.ratelimit.RateLimitService
import com.example.linksphere.infra.ai.GeminiService
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 글 삭제가 댓글 이미지를 정리하는지 실제 Postgres로 본다(docs/plans/2026-11-16-be-structure-stage2.md PR-8).
 *
 * post는 comment를 모르고 "곧 지운다"(PostDeletingEvent)고만 알린다. 듣는 쪽이 빠져도 예외 없이 조용히 이미지가 남으므로
 * 실제 이벤트 전달로 확인한다. 파일 삭제는 커밋 뒤에 실행되므로 테스트 트랜잭션 밖에서 돌고 끝나면 지운다.
 * 알리는 순서(삭제 전)는 여기서 가려지지 않는다 - JPA가 삭제 SQL을 커밋 직전까지 미뤄 순서를 뒤집어도 댓글이 읽힌다.
 * 순서는 PostServiceTest가 본다.
 */
@PostgresIntegrationTest
@Import(PostService::class, PostSearchFlow::class, PostResponseAssembler::class, PostInteractionAdapter::class, CommentService::class, CommentCountAdapter::class, CommentPostEventListener::class, PostLookupService::class, PostLinkParser::class, CategoryService::class, MemberService::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostDeleteCommentImageIntegrationTest {

    @Autowired private lateinit var postService: PostService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var urlMetadataExtractor: UrlMetadataExtractor

    @MockitoBean private lateinit var safeUrlValidator: SafeUrlValidator

    @MockitoBean private lateinit var geminiService: GeminiService

    @MockitoBean private lateinit var linkPreviewService: LinkPreviewService

    @MockitoBean private lateinit var advisoryLock: AdvisoryLock

    @MockitoBean private lateinit var rateLimitService: RateLimitService

    @MockitoBean private lateinit var supabaseStorageService: SupabaseStorageService

    private val writer = UUID.randomUUID()
    private val deletedPost = UUID.randomUUID()
    private val otherPost = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        jdbc.update("INSERT INTO members (id, nickname) VALUES (?, 'writer')", writer)
        listOf(deletedPost, otherPost).forEach {
            jdbc.update("INSERT INTO posts (id, user_id, url, title, is_private) VALUES (?, ?, 'https://example.com', 't', false)", it, writer)
        }
        `when`(supabaseStorageService.isManagedUrl(anyString())).thenAnswer { (it.arguments[0] as String).startsWith(BUCKET) }
    }

    @AfterEach
    fun cleanUp() {
        // members를 지우면 posts → comments가 FK cascade로 함께 지워진다
        jdbc.update("DELETE FROM members WHERE id = ?", writer)
    }

    @Test
    fun `글을 지우면 그 글의 댓글에만 든 이미지를 커밋 뒤에 지우고 댓글 행도 사라진다`() {
        val onlyHere = "$BUCKET/only-here.png"
        val sharedWithOtherPost = "$BUCKET/shared.png"
        insertComment(deletedPost, "첫 댓글\n\n$onlyHere")
        insertComment(deletedPost, "둘째 댓글\n\n$sharedWithOtherPost\nhttps://elsewhere.example.com/a.png")
        insertComment(otherPost, "다른 글의 댓글\n\n$sharedWithOtherPost")

        postService.deletePost(deletedPost, writer)

        // 다른 글의 댓글이 아직 쓰는 이미지와 우리 버킷이 아닌 URL은 지우지 않는다
        verify(supabaseStorageService).deleteObjectsByPublicUrls(listOf(onlyHere))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM comments WHERE post_id = ?", Int::class.java, deletedPost))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM comments WHERE post_id = ?", Int::class.java, otherPost))
    }

    private fun insertComment(post: UUID, content: String) {
        jdbc.update("INSERT INTO comments (id, post_id, user_id, content) VALUES (?, ?, ?, ?)", UUID.randomUUID(), post, writer, content)
    }

    companion object {
        private const val BUCKET = "https://storage.example.com/bucket"
    }
}
