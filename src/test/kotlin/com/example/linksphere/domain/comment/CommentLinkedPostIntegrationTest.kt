package com.example.linksphere.domain.comment

import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.post.PostLinkParser
import com.example.linksphere.domain.post.PostLookupService
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

/**
 * 댓글에 붙은 우리 글 주소의 링크 카드를 Service부터 실제 Postgres까지 본다(docs/plans/2026-11-19-comment-post-link-preview.md).
 *
 * 우리 글 주소는 어떤 글이든 같은 SPA 껍데기 HTML이라, 크롤링해 저장하던 때에는 카드 제목이 전부 "Link Sphere"였고 썸네일이 없었다.
 * 지금은 댓글을 줄 때마다 post에 "보는 사람이 볼 수 있는 글의 제목·설명·썸네일"을 묻는다. 그래서 "글을 고치면 따라간다"와
 * "비공개 글의 제목이 새지 않는다"는 comment의 조립과 post의 쿼리가 함께 돌아야 확인된다.
 */
@PostgresIntegrationTest
@Import(CommentService::class, PostLookupService::class, PostLinkParser::class, MemberService::class)
@TestPropertySource(properties = ["app.frontend.url=https://linksphere.click"])
class CommentLinkedPostIntegrationTest {

    @Autowired private lateinit var commentService: CommentService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var supabaseStorageService: SupabaseStorageService

    @MockitoBean private lateinit var advisoryLock: AdvisoryLock

    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    // 댓글이 달리는 글
    private val thread = UUID.randomUUID()

    // 댓글 본문의 주소가 가리키는 글 - 둘 다 other가 썼다
    private val linkedPublic = UUID.randomUUID()
    private val linkedPrivate = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        listOf(me, other).forEach {
            jdbc.update("INSERT INTO members (id, email, nickname, password) VALUES (?, ?, ?, 'enc')", it, "$it@example.com", it.toString())
        }
        insertPost(thread, title = "댓글이 달리는 글")
        insertPost(linkedPublic, title = "공개 글", description = "공개 글 설명", ogImage = "https://img.example.com/public.png")
        insertPost(linkedPrivate, title = "비공개 글", isPrivate = true)
    }

    @Test
    fun `공개 글 주소가 달린 댓글은 크롤링 저장값이 없어도 그 글의 제목·설명·썸네일을 싣는다`() {
        val url = linkTo(linkedPublic)
        val comment = insertComment(linkUrl = url)

        assertEquals(
            LinkMetadata(url = url, title = "공개 글", description = "공개 글 설명", ogImage = "https://img.example.com/public.png"),
            linkMetadataOf(comment, viewer = me),
        )
    }

    // 운영에 남아 있는 모양이다 - 고치기 전 후처리 잡이 우리 글 주소를 크롤링해 제목 "Link Sphere"만 저장했다
    @Test
    fun `예전에 크롤링돼 제목이 Link Sphere로 저장된 댓글도 글의 값으로 나오고 주소는 적힌 그대로 나간다`() {
        val url = "https://www.linksphere.click/post/$linkedPublic#comment-${UUID.randomUUID()}"
        val comment = insertComment(linkUrl = url, linkTitle = "Link Sphere")

        assertEquals(
            LinkMetadata(url = url, title = "공개 글", description = "공개 글 설명", ogImage = "https://img.example.com/public.png"),
            linkMetadataOf(comment, viewer = me),
        )
    }

    // 저장할 때 복사해 두는 방식이면 옛 제목이 남는다
    @Test
    fun `글 제목을 고치면 댓글을 다시 쓰지 않아도 다음 조회에 새 제목이 나온다`() {
        val comment = insertComment(linkUrl = linkTo(linkedPublic))

        assertEquals("공개 글", linkMetadataOf(comment, viewer = me)?.title)

        jdbc.update("UPDATE posts SET title = '고친 제목' WHERE id = ?", linkedPublic)

        assertEquals("고친 제목", linkMetadataOf(comment, viewer = me)?.title)
    }

    @Test
    fun `남의 비공개 글 주소는 카드를 싣지 않고 그 글을 쓴 사람에게만 싣는다`() {
        val comment = insertComment(linkUrl = linkTo(linkedPrivate))

        assertNull(linkMetadataOf(comment, viewer = me))
        assertEquals("비공개 글", linkMetadataOf(comment, viewer = other)?.title)
    }

    @Test
    fun `댓글을 쓴 뒤 그 글이 비공개로 바뀌면 남에게는 카드가 사라진다`() {
        val comment = insertComment(linkUrl = linkTo(linkedPublic))

        assertEquals("공개 글", linkMetadataOf(comment, viewer = me)?.title)

        jdbc.update("UPDATE posts SET is_private = true WHERE id = ?", linkedPublic)

        assertNull(linkMetadataOf(comment, viewer = me))
    }

    @Test
    fun `비로그인에게는 공개 글의 카드만 나간다`() {
        val onPublic = insertComment(linkUrl = linkTo(linkedPublic))
        val onPrivate = insertComment(linkUrl = linkTo(linkedPrivate))

        assertEquals("공개 글", linkMetadataOf(onPublic, viewer = null)?.title)
        assertNull(linkMetadataOf(onPrivate, viewer = null))
    }

    // 글이 지워졌거나 주소의 id가 처음부터 없는 글이다 - 남의 비공개 글과 똑같이 카드가 없어 둘을 구분할 수 없다
    @Test
    fun `없는 글의 주소는 카드를 싣지 않는다`() {
        val comment = insertComment(linkUrl = linkTo(UUID.randomUUID()), linkTitle = "Link Sphere")

        assertNull(linkMetadataOf(comment, viewer = me))
    }

    @Test
    fun `다른 사이트 주소는 지금처럼 크롤링 저장값을 싣고 크롤링 전이면 제목 자리에 주소를 싣는다`() {
        val crawledUrl = "https://example.com/article"
        val pendingUrl = "https://example.com/pending"
        val crawled = insertComment(linkUrl = crawledUrl, linkTitle = "Article", linkDescription = "desc", linkOgImage = "https://img.example.com/a.png")
        val pending = insertComment(linkUrl = pendingUrl)

        assertEquals(
            LinkMetadata(url = crawledUrl, title = "Article", description = "desc", ogImage = "https://img.example.com/a.png"),
            linkMetadataOf(crawled, viewer = me),
        )
        assertEquals(LinkMetadata(url = pendingUrl, title = pendingUrl, description = null, ogImage = null), linkMetadataOf(pending, viewer = me))
    }

    @Test
    fun `삭제된 댓글은 우리 글 주소가 있어도 카드를 싣지 않고 그 답글의 카드는 싣는다`() {
        val parent = insertComment(linkUrl = linkTo(linkedPublic), isDeleted = true)
        val reply = insertComment(linkUrl = linkTo(linkedPublic), parent = parent)

        assertNull(linkMetadataOf(parent, viewer = me))
        assertEquals("공개 글", linkMetadataOf(reply, viewer = me)?.title)
    }

    // 등록 응답은 FE가 목록을 다시 받지 않고 그대로 화면에 꽂는다 - 여기에 실려야 등록 직후부터 카드가 보인다
    @Test
    fun `댓글을 등록한 응답에 링크된 글의 카드가 바로 실리고 볼 수 없는 글이면 실리지 않는다`() {
        val onPublic = commentService.createComment(thread, me, "이 글 보세요 ${linkTo(linkedPublic)}", null)
        val onPrivate = commentService.createComment(thread, me, "이 글도요 ${linkTo(linkedPrivate)}", null)

        assertEquals("공개 글", onPublic.linkMetadata?.title)
        assertEquals("https://img.example.com/public.png", onPublic.linkMetadata?.ogImage)
        assertNull(onPrivate.linkMetadata)
    }

    private fun linkTo(postId: UUID) = "https://linksphere.click/post/$postId"

    // 답글은 부모의 replies 안에 실려 온다
    private fun linkMetadataOf(commentId: UUID, viewer: UUID?): LinkMetadata? = commentService.getComments(thread, viewer)
        .flatMap { listOf(it) + it.replies }
        .single { it.id == commentId }
        .linkMetadata

    private fun insertPost(id: UUID, title: String, description: String? = null, ogImage: String? = null, isPrivate: Boolean = false) {
        jdbc.update(
            "INSERT INTO posts (id, user_id, url, title, description, og_image, is_private) VALUES (?, ?, 'https://example.com', ?, ?, ?, ?)",
            id,
            other,
            title,
            description,
            ogImage,
            isPrivate,
        )
    }

    private fun insertComment(
        linkUrl: String,
        linkTitle: String? = null,
        linkDescription: String? = null,
        linkOgImage: String? = null,
        isDeleted: Boolean = false,
        parent: UUID? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO comments (id, post_id, user_id, parent_id, content, is_deleted, link_url, link_title, link_description, link_og_image) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id,
            thread,
            me,
            parent,
            "이 글 보세요 $linkUrl",
            isDeleted,
            linkUrl,
            linkTitle,
            linkDescription,
            linkOgImage,
        )

        return id
    }
}
