package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.comment.CommentCountAdapter
import com.example.linksphere.domain.interaction.PostInteractionAdapter
import com.example.linksphere.domain.linkpreview.LinkPreviewService
import com.example.linksphere.domain.linkpreview.SafeUrlValidator
import com.example.linksphere.domain.linkpreview.UrlMetadata
import com.example.linksphere.domain.linkpreview.UrlMetadataExtractor
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.ratelimit.RateLimitService
import com.example.linksphere.infra.ai.GeminiService
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
 * 글 응답의 좋아요·북마크와 글 등록 때의 북마크 저장을 실제 Postgres로 본다(docs/plans/2026-11-16-be-structure-stage2.md PR-9a).
 *
 * post는 interaction의 저장소를 모르고 PostInteractionPort에 묻는다. 구현(PostInteractionAdapter)이 빈으로 연결돼 실제 SQL까지
 * 가는지, 그리고 글 등록 때의 북마크·폴더 소속이 FK를 지켜 실제로 저장되는지는 mock으로 보이지 않는다.
 */
@PostgresIntegrationTest
@Import(PostService::class, PostResponseAssembler::class, PostInteractionAdapter::class, CommentCountAdapter::class, CategoryService::class, MemberService::class)
class PostInteractionPortIntegrationTest {

    @Autowired private lateinit var postService: PostService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var urlMetadataExtractor: UrlMetadataExtractor

    @MockitoBean private lateinit var safeUrlValidator: SafeUrlValidator

    @MockitoBean private lateinit var geminiService: GeminiService

    @MockitoBean private lateinit var linkPreviewService: LinkPreviewService

    @MockitoBean private lateinit var advisoryLock: AdvisoryLock

    @MockitoBean private lateinit var rateLimitService: RateLimitService

    private val writer = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private val busyPost = UUID.randomUUID()
    private val quietPost = UUID.randomUUID()
    private val viewerFolderA = UUID.randomUUID()
    private val viewerFolderB = UUID.randomUUID()
    private val othersFolder = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        listOf(writer to "writer", viewer to "viewer", other to "other").forEach { (id, nickname) ->
            jdbc.update("INSERT INTO members (id, nickname, email_verified) VALUES (?, ?, true)", id, nickname)
        }
        jdbc.update("INSERT INTO posts (id, user_id, url, title, is_private, created_at) VALUES (?, ?, 'https://example.com/busy', 'busy', false, now() - interval '1 hour')", busyPost, writer)
        jdbc.update("INSERT INTO posts (id, user_id, url, title, is_private, created_at) VALUES (?, ?, 'https://example.com/quiet', 'quiet', false, now() - interval '2 hours')", quietPost, writer)
        jdbc.update("INSERT INTO bookmark_folders (id, user_id, name) VALUES (?, ?, 'A')", viewerFolderA, viewer)
        jdbc.update("INSERT INTO bookmark_folders (id, user_id, name) VALUES (?, ?, 'B')", viewerFolderB, viewer)
        jdbc.update("INSERT INTO bookmark_folders (id, user_id, name) VALUES (?, ?, 'X')", othersFolder, other)

        // busyPost: 좋아요 2(viewer·other), 북마크 2(viewer는 폴더 A·B, other는 미분류)
        listOf(viewer, other).forEach {
            jdbc.update("INSERT INTO post_reactions (user_id, post_id) VALUES (?, ?)", it, busyPost)
            jdbc.update("INSERT INTO bookmarks (user_id, post_id) VALUES (?, ?)", it, busyPost)
        }
        listOf(viewerFolderA, viewerFolderB).forEach {
            jdbc.update("INSERT INTO bookmark_folder_items (user_id, post_id, folder_id) VALUES (?, ?, ?)", viewer, busyPost, it)
        }
    }

    @Test
    fun `글 목록은 글마다 좋아요·북마크 수와 내 상태를 싣는다`() {
        val page = postService.getPostsByCursor(null, null, null, null, null, 20, viewer).content.associateBy { it.id }

        val busy = page.getValue(busyPost)
        assertEquals(2, busy.stats.likeCount)
        assertEquals(2, busy.stats.bookmarkCount)
        assertEquals(true, busy.userInteractions.isLiked)
        assertEquals(true, busy.userInteractions.isBookmarked)
        assertEquals(setOf(viewerFolderA, viewerFolderB), busy.userInteractions.bookmarkFolderIds.toSet())

        val quiet = page.getValue(quietPost)
        assertEquals(0, quiet.stats.likeCount)
        assertEquals(0, quiet.stats.bookmarkCount)
        assertEquals(false, quiet.userInteractions.isLiked)
        assertEquals(false, quiet.userInteractions.isBookmarked)
        assertEquals(emptyList<UUID>(), quiet.userInteractions.bookmarkFolderIds)
    }

    // 남의 폴더 소속(other의 미분류 북마크, viewer의 폴더)이 다른 사람의 응답에 섞이지 않아야 한다
    @Test
    fun `글 목록의 내 상태는 보는 사람마다 다르고 비로그인은 모두 false다`() {
        val asWriter = postService.getPostsByCursor(null, null, null, null, null, 20, writer).content.first { it.id == busyPost }
        val asOther = postService.getPostsByCursor(null, null, null, null, null, 20, other).content.first { it.id == busyPost }
        val anonymous = postService.getPostsByCursor(null, null, null, null, null, 20, null).content.first { it.id == busyPost }

        assertEquals(PostUserInteractions(isLiked = false, isBookmarked = false, bookmarkFolderIds = emptyList()), asWriter.userInteractions)
        assertEquals(PostUserInteractions(isLiked = true, isBookmarked = true, bookmarkFolderIds = emptyList()), asOther.userInteractions)
        assertEquals(PostUserInteractions(isLiked = false, isBookmarked = false, bookmarkFolderIds = emptyList()), anonymous.userInteractions)
        assertEquals(2, anonymous.stats.likeCount)
        assertEquals(2, anonymous.stats.bookmarkCount)
    }

    @Test
    fun `글 상세도 같은 값을 싣는다`() {
        val detail = postService.getPostById(busyPost, viewer)
        val anonymous = postService.getPostById(busyPost, null)

        assertEquals(2, detail.stats.likeCount)
        assertEquals(2, detail.stats.bookmarkCount)
        assertEquals(true, detail.userInteractions.isLiked)
        assertEquals(true, detail.userInteractions.isBookmarked)
        assertEquals(setOf(viewerFolderA, viewerFolderB), detail.userInteractions.bookmarkFolderIds.toSet())
        assertEquals(PostUserInteractions(isLiked = false, isBookmarked = false, bookmarkFolderIds = emptyList()), anonymous.userInteractions)
        assertEquals(2, anonymous.stats.bookmarkCount)
    }

    // 북마크 insert는 네이티브 쿼리라 글 행이 DB에 먼저 나가 있어야 한다(FK). PostService가 flush를 부르는지는 여기서 가려지지
    // 않는다 - flush를 빼도 통과한다(원인은 추적하지 않았다. Hibernate가 네이티브 쿼리 앞에서 밀린 쓰기를 내보내는 것으로
    // 추정한다). 호출 순서는 PostServiceTest가 본다
    @Test
    fun `폴더를 지정해 글을 등록하면 북마크와 폴더 소속이 함께 저장된다`() {
        stubMetadata()

        val created = postService.createPost(viewer, PostCreateRequest(url = "https://example.com/new", folderIds = listOf(viewerFolderA, viewerFolderB, viewerFolderA)))

        assertEquals(true, created.userInteractions.isBookmarked)
        assertEquals(setOf(viewerFolderA, viewerFolderB), created.userInteractions.bookmarkFolderIds.toSet())
        assertEquals(1, created.stats.bookmarkCount)
        assertEquals(1, count("SELECT count(*) FROM bookmarks WHERE user_id = ? AND post_id = ?", viewer, created.id))
        assertEquals(2, count("SELECT count(*) FROM bookmark_folder_items WHERE user_id = ? AND post_id = ?", viewer, created.id))
    }

    @Test
    fun `북마크만 켜고 등록하면 폴더 없이 북마크만 저장된다`() {
        stubMetadata()

        val created = postService.createPost(viewer, PostCreateRequest(url = "https://example.com/new", bookmark = true))

        assertEquals(PostUserInteractions(isLiked = false, isBookmarked = true, bookmarkFolderIds = emptyList()), created.userInteractions)
        assertEquals(0, count("SELECT count(*) FROM bookmark_folder_items WHERE user_id = ? AND post_id = ?", viewer, created.id))
    }

    @Test
    fun `남의 폴더나 없는 폴더를 지정하면 북마크를 저장하지 않고 실패한다`() {
        stubMetadata()

        assertThrows(ForbiddenException::class.java) {
            postService.createPost(viewer, PostCreateRequest(url = "https://example.com/foreign", folderIds = listOf(viewerFolderA, othersFolder)))
        }
        assertThrows(BookmarkFolderNotFoundException::class.java) {
            postService.createPost(viewer, PostCreateRequest(url = "https://example.com/missing", folderIds = listOf(UUID.randomUUID())))
        }

        assertEquals(0, count("SELECT count(*) FROM bookmarks b JOIN posts p ON p.id = b.post_id WHERE b.user_id = ? AND p.url <> ?", viewer, "https://example.com/busy"))
    }

    // 폴더가 있는지를 먼저 보고 그다음 주인을 본다 - 순서가 바뀌면 같은 요청의 응답이 404에서 403으로 달라진다
    @Test
    fun `없는 폴더와 남의 폴더를 함께 지정하면 없는 폴더로 실패한다`() {
        stubMetadata()

        assertThrows(BookmarkFolderNotFoundException::class.java) {
            postService.createPost(viewer, PostCreateRequest(url = "https://example.com/both", folderIds = listOf(othersFolder, UUID.randomUUID())))
        }
    }

    private fun stubMetadata() {
        `when`(urlMetadataExtractor.extract(anyString()))
            .thenReturn(UrlMetadata(title = "제목", description = null, ogImage = null, tags = emptyList(), pageContent = null))
    }

    private fun count(sql: String, vararg args: Any?): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!
}
