package com.example.linksphere.domain.post

import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID

/**
 * 미리보기 봇에 주는 OG HTML이 공개 글만 글의 값을 싣는지 실제 Postgres로 본다(docs/plans/2026-11-19-post-link-in-app-and-og.md
 * Phase 2-1 "비공개 글 노출"). 봇은 토큰을 보내지 않아 항상 비로그인으로 묻는다 - 글쓴이 본인의 비공개 글도 기본값이다.
 */
@PostgresIntegrationTest
@Import(PostOgService::class, PostLookupService::class)
@TestPropertySource(properties = ["app.frontend.url=https://linksphere.click"])
class PostOgIntegrationTest {

    @Autowired private lateinit var postOgService: PostOgService

    @Autowired private lateinit var jdbc: JdbcTemplate

    private val author = UUID.randomUUID()

    private val publicPost = UUID.randomUUID()
    private val privatePost = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        jdbc.update("INSERT INTO members (id, email, nickname, password) VALUES (?, ?, ?, 'enc')", author, "$author@example.com", author.toString())
        insertPost(publicPost, title = "공개 글", description = "공개 글 설명", ogImage = "https://img.example.com/public.png", isPrivate = false)
        insertPost(privatePost, title = "비공개 글", description = "비공개 설명", ogImage = "https://img.example.com/private.png", isPrivate = true)
    }

    @Test
    fun `공개 글은 그 글의 제목·설명·썸네일을 싣는다`() {
        val html = postOgService.renderPostHtml(publicPost.toString())

        assertTrue(html.contains("""<meta property="og:title" content="공개 글">"""), html)
        assertTrue(html.contains("""<meta property="og:description" content="공개 글 설명">"""), html)
        assertTrue(html.contains("""<meta property="og:image" content="https://img.example.com/public.png">"""), html)
    }

    @Test
    fun `비공개 글은 글쓴이가 있어도 제목·설명·썸네일을 싣지 않는다`() {
        val html = postOgService.renderPostHtml(privatePost.toString())

        assertTrue(html.contains("""<meta property="og:title" content="Link Sphere">"""), html)
        assertFalse(html.contains("비공개"), html)
        assertFalse(html.contains("og:image"), html)
    }

    // 글은 행을 지운다 - 지운 글과 처음부터 없는 id는 같은 경우다
    @Test
    fun `없는 글이나 지운 글은 기본값을 싣는다`() {
        jdbc.update("DELETE FROM posts WHERE id = ?", publicPost)

        val html = postOgService.renderPostHtml(publicPost.toString())

        assertTrue(html.contains("""<meta property="og:title" content="Link Sphere">"""), html)
        assertFalse(html.contains("공개 글"), html)
    }

    @Test
    fun `글을 비공개로 바꾸면 다음 요청부터 기본값을 싣는다`() {
        assertTrue(postOgService.renderPostHtml(publicPost.toString()).contains("공개 글 설명"))

        jdbc.update("UPDATE posts SET is_private = true WHERE id = ?", publicPost)

        assertFalse(postOgService.renderPostHtml(publicPost.toString()).contains("공개 글 설명"))
    }

    private fun insertPost(id: UUID, title: String, description: String?, ogImage: String?, isPrivate: Boolean) {
        jdbc.update(
            "INSERT INTO posts (id, user_id, url, title, description, og_image, is_private) VALUES (?, ?, 'https://example.com', ?, ?, ?, ?)",
            id,
            author,
            title,
            description,
            ogImage,
            isPrivate,
        )
    }
}
