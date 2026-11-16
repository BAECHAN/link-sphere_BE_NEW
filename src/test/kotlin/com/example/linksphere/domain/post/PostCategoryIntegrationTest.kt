package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryResponse
import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.comment.CommentService
import com.example.linksphere.domain.linkpreview.LinkPreviewService
import com.example.linksphere.domain.linkpreview.SafeUrlValidator
import com.example.linksphere.domain.linkpreview.UrlMetadata
import com.example.linksphere.domain.linkpreview.UrlMetadataExtractor
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.global.ratelimit.RateLimitService
import com.example.linksphere.infra.ai.GeminiService
import com.example.linksphere.support.PostgresIntegrationTest
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

/**
 * 글과 카테고리의 연결을 실제 Postgres로 본다(docs/plans/2026-11-16-be-structure-stage2.md PR-4).
 *
 * 글은 카테고리 엔티티 대신 id 집합만 들고(post_categories), 이름은 category에 물어 붙인다. 그래서 "카테고리로 거르기",
 * "응답에 이름 붙이기", "없는 id 버리기", "연결 행 쓰고 지우기"가 SQL과 FK까지 가야 확인된다 - mock으로는 보이지 않는다.
 */
@PostgresIntegrationTest
@Import(PostService::class, PostResponseAssembler::class, CommentService::class, CategoryService::class)
class PostCategoryIntegrationTest {

    @Autowired private lateinit var postService: PostService

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var urlMetadataExtractor: UrlMetadataExtractor

    @MockitoBean private lateinit var safeUrlValidator: SafeUrlValidator

    @MockitoBean private lateinit var geminiService: GeminiService

    @MockitoBean private lateinit var linkPreviewService: LinkPreviewService

    @MockitoBean private lateinit var advisoryLock: AdvisoryLock

    @MockitoBean private lateinit var rateLimitService: RateLimitService

    @MockitoBean private lateinit var supabaseStorageService: SupabaseStorageService

    private val writer = UUID.randomUUID()
    private val frontendPost = UUID.randomUUID()
    private val bothPost = UUID.randomUUID()
    private val backendPost = UUID.randomUUID()
    private val plainPost = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        jdbc.update("INSERT INTO members (id, nickname) VALUES (?, 'writer')", writer)
        jdbc.update(
            "INSERT INTO categories (id, name, slug) VALUES " +
                "($FRONTEND, 'Frontend', 'frontend'), ($BACKEND, 'Backend', 'backend'), ($DEVOPS, 'DevOps', 'dev-ops')",
        )
        insertPost(frontendPost, FRONTEND)
        insertPost(bothPost, BACKEND, FRONTEND)
        insertPost(backendPost, BACKEND)
        insertPost(plainPost)
    }

    @Test
    fun `slug 로 거르면 그 카테고리의 글만 나온다`() {
        assertEquals(setOf(frontendPost, bothPost), feedIds("frontend"))
    }

    @Test
    fun `이름으로 걸러도 같은 글이 나온다`() {
        assertEquals(setOf(frontendPost, bothPost), feedIds("Frontend"))
    }

    // 두 카테고리에 모두 든 글도 한 번만 나온다 - 연결 테이블과 조인하지 않고 EXISTS로 거르기 때문이다
    @Test
    fun `쉼표로 여러 개를 주면 하나라도 든 글이 한 번씩 나온다`() {
        val page = postService.getPostsByCursor("frontend, Backend", null, null, null, null, 20, null)

        assertEquals(3, page.content.size)
        assertEquals(setOf(frontendPost, bothPost, backendPost), page.content.map { it.id }.toSet())
    }

    // 이름을 id로 푼 결과가 비었을 때 "거르지 않음"으로 읽으면 전체 글이 나온다 - 그 실수를 막는다
    @Test
    fun `맞는 카테고리가 없으면 전체 글이 아니라 빈 결과다`() {
        assertEquals(emptySet<UUID>(), feedIds("no-such-category"))
    }

    @Test
    fun `글이 없는 카테고리로 거르면 빈 결과다`() {
        assertEquals(emptySet<UUID>(), feedIds("dev-ops"))
    }

    @Test
    fun `category 가 비어 있으면 거르지 않는다`() {
        assertEquals(setOf(frontendPost, bothPost, backendPost, plainPost), feedIds(" , "))
    }

    @Test
    fun `응답의 카테고리는 id 순서로 이름이 붙는다`() {
        val post = postService.getPostById(bothPost, null)

        assertEquals(listOf(CategoryResponse(FRONTEND, "Frontend"), CategoryResponse(BACKEND, "Backend")), post.categories)
    }

    // 연결 테이블의 category_id는 categories를 가리키는 FK라, 없는 id를 그대로 넣으면 등록이 통째로 실패한다
    @Test
    fun `등록할 때 없는 카테고리 id 는 버리고 있는 것만 연결한다`() {
        `when`(urlMetadataExtractor.extract(anyString()))
            .thenReturn(UrlMetadata(title = "제목", description = null, ogImage = null, tags = emptyList(), pageContent = null))

        val created =
            postService.createPost(writer, PostCreateRequest(url = "https://example.com/new", categoryIds = listOf(BACKEND, MISSING)))
        entityManager.flush()

        assertEquals(listOf(CategoryResponse(BACKEND, "Backend")), created.categories)
        assertEquals(listOf(BACKEND), categoryIdsInDb(created.id))
    }

    @Test
    fun `수정하면 연결이 새 목록으로 바뀐다`() {
        val updated = postService.updatePost(frontendPost, writer, PostUpdateRequest(title = "t", categoryIds = listOf(DEVOPS, BACKEND, MISSING)))
        entityManager.flush()

        assertEquals(listOf(CategoryResponse(BACKEND, "Backend"), CategoryResponse(DEVOPS, "DevOps")), updated.categories)
        assertEquals(listOf(BACKEND, DEVOPS), categoryIdsInDb(frontendPost))
    }

    @Test
    fun `수정에서 카테고리를 비우면 연결이 모두 지워진다`() {
        postService.updatePost(bothPost, writer, PostUpdateRequest(title = "t", categoryIds = emptyList()))
        entityManager.flush()

        assertEquals(emptyList<Long>(), categoryIdsInDb(bothPost))
    }

    @Test
    fun `글을 지우면 연결도 지워지고 카테고리는 남는다`() {
        postService.deletePost(bothPost, writer)
        entityManager.flush()

        assertEquals(emptyList<Long>(), categoryIdsInDb(bothPost))
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM categories WHERE id IN ($FRONTEND, $BACKEND, $DEVOPS)", Int::class.java))
    }

    private fun feedIds(category: String): Set<UUID> = postService.getPostsByCursor(category, null, null, null, null, 20, null).content.map { it.id }.toSet()

    private fun categoryIdsInDb(postId: UUID): List<Long> = jdbc.queryForList("SELECT category_id FROM post_categories WHERE post_id = ? ORDER BY category_id", Long::class.java, postId)

    private fun insertPost(id: UUID, vararg categoryIds: Long) {
        jdbc.update("INSERT INTO posts (id, user_id, url, title, is_private) VALUES (?, ?, 'https://example.com', 't', false)", id, writer)
        categoryIds.forEach { jdbc.update("INSERT INTO post_categories (post_id, category_id) VALUES (?, ?)", id, it) }
    }

    companion object {
        private const val FRONTEND = 9101L
        private const val BACKEND = 9102L
        private const val DEVOPS = 9103L
        private const val MISSING = 9199L
    }
}
