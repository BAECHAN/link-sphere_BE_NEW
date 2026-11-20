package com.example.linksphere.domain.post

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

private const val SITE = "https://linksphere.click"

// FE index.html의 <meta name="description">과 같은 문장이다 - 사람이 여는 앱 HTML과 봇이 받는 기본값이 같은 소개를 쓴다
private const val SITE_DESCRIPTION_TEXT = "유용한 아티클과 리소스 링크를 공유하고, 다른 사람이 모은 링크를 발견하는 소셜 플랫폼이에요."

// 메신저 미리보기 봇이 글 주소를 열면 CloudFront Function이 이 HTML로 보낸다(docs/plans/2026-11-19-post-link-in-app-and-og.md Phase 2).
// 봇은 <meta property="og:*">만 읽는다 - 볼 수 없는 글은 사이트 이름·소개만 싣고 이미지를 넣지 않는다(사용자 결정).
class PostOgServiceTest {

    private val postLookupService: PostLookupService = mock(PostLookupService::class.java)

    private val service = PostOgService(postLookupService, SITE)

    private val postId = UUID.randomUUID()

    private fun givenPreview(preview: PostLinkPreview?) {
        `when`(postLookupService.findVisiblePreviews(listOf(postId), null))
            .thenReturn(preview?.let { mapOf(postId to it) } ?: emptyMap())
    }

    @Test
    fun `공개 글이면 그 글의 제목·설명·썸네일과 글 주소를 싣는다`() {
        givenPreview(PostLinkPreview(title = "공개 글", description = "공개 글 설명", ogImage = "https://img.example.com/a.png"))

        val html = service.renderPostHtml(postId.toString())

        assertTrue(html.contains("<title>공개 글</title>"), html)
        assertTrue(html.contains("""<meta property="og:title" content="공개 글">"""), html)
        assertTrue(html.contains("""<meta property="og:description" content="공개 글 설명">"""), html)
        assertTrue(html.contains("""<meta name="description" content="공개 글 설명">"""), html)
        assertTrue(html.contains("""<meta property="og:image" content="https://img.example.com/a.png">"""), html)
        assertTrue(html.contains("""<meta property="og:url" content="$SITE/post/$postId">"""), html)
        assertTrue(html.contains("""<meta property="og:type" content="article">"""), html)
        assertTrue(html.contains("""<meta property="og:site_name" content="Link Sphere">"""), html)
        assertTrue(html.contains("""<meta name="twitter:card" content="summary_large_image">"""), html)
    }

    // 사이트 소개를 대신 넣으면 글 설명처럼 보인다
    @Test
    fun `설명·썸네일이 없는 글이면 그 태그를 싣지 않고 작은 카드로 둔다`() {
        givenPreview(PostLinkPreview(title = "설명 없는 글", description = null, ogImage = null))

        val html = service.renderPostHtml(postId.toString())

        assertTrue(html.contains("""<meta property="og:title" content="설명 없는 글">"""), html)
        assertFalse(html.contains("og:description"), html)
        assertFalse(html.contains("""name="description""""), html)
        assertFalse(html.contains("og:image"), html)
        assertTrue(html.contains("""<meta name="twitter:card" content="summary">"""), html)
    }

    @Test
    fun `설명·썸네일이 빈 문자열이어도 싣지 않는다`() {
        givenPreview(PostLinkPreview(title = "빈 값", description = "  ", ogImage = ""))

        val html = service.renderPostHtml(postId.toString())

        assertFalse(html.contains("og:description"), html)
        assertFalse(html.contains("og:image"), html)
    }

    // 남의 비공개 글·지운 글·없는 글을 구분하지 않는다 - 존재 여부도 새지 않게
    @Test
    fun `볼 수 없는 글이면 사이트 이름·소개만 싣고 이미지는 넣지 않는다`() {
        givenPreview(null)

        val html = service.renderPostHtml(postId.toString())

        assertTrue(html.contains("<title>Link Sphere</title>"), html)
        assertTrue(html.contains("""<meta property="og:title" content="Link Sphere">"""), html)
        assertTrue(html.contains("""<meta property="og:description" content="$SITE_DESCRIPTION_TEXT">"""), html)
        assertTrue(html.contains("""<meta property="og:type" content="website">"""), html)
        assertTrue(html.contains("""<meta name="twitter:card" content="summary">"""), html)
        assertFalse(html.contains("og:image"), html)
    }

    // 봇은 토큰을 보내지 않는다 - 글쓴이 본인의 비공개 글도 메신저 미리보기에는 기본값이 나와야 한다
    @Test
    fun `글은 항상 비로그인으로 묻는다`() {
        givenPreview(null)

        service.renderPostHtml(postId.toString())

        verify(postLookupService).findVisiblePreviews(listOf(postId), null)
    }

    @Test
    fun `id가 UUID가 아니면 글을 묻지 않고 기본값을 사이트 주소로 싣는다`() {
        val html = service.renderPostHtml("not-a-uuid")

        assertTrue(html.contains("<title>Link Sphere</title>"), html)
        assertTrue(html.contains("""<meta property="og:url" content="$SITE">"""), html)
        verify(postLookupService, never()).findVisiblePreviews(anyCollection(), org.mockito.ArgumentMatchers.any())
    }

    // UUID.fromString은 "1-1-1-1-1"처럼 자릿수가 모자란 문자열도 받는다 - 글 주소의 id는 FE 라우트처럼 정식 UUID만이다
    @Test
    fun `자릿수가 모자란 UUID 모양 문자열도 글로 보지 않는다`() {
        service.renderPostHtml("1-1-1-1-1")

        verify(postLookupService, never()).findVisiblePreviews(anyCollection(), org.mockito.ArgumentMatchers.any())
    }

    // 제목·설명·썸네일 주소는 사용자가 넣은 값이다 - 속성 밖으로 나가 태그를 만들면 안 된다
    @Test
    fun `글의 값에 든 따옴표·꺾쇠·앰퍼샌드를 이스케이프한다`() {
        givenPreview(
            PostLinkPreview(
                title = """"><script>alert(1)</script>&""",
                description = "a 'b' <c>",
                ogImage = """https://img.example.com/a.png?x=1&y="2"""",
            ),
        )

        val html = service.renderPostHtml(postId.toString())

        assertFalse(html.contains("<script>"), html)
        assertTrue(html.contains("""content="&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;&amp;""""), html)
        assertTrue(html.contains("""content="a &#39;b&#39; &lt;c&gt;""""), html)
        assertTrue(html.contains("""content="https://img.example.com/a.png?x=1&amp;y=&quot;2&quot;""""), html)
    }

    @Test
    fun `사이트 주소 끝의 슬래시는 한 번만 쓴다`() {
        givenPreview(null)

        val html = PostOgService(postLookupService, "$SITE/").renderPostHtml(postId.toString())

        assertTrue(html.contains("""<meta property="og:url" content="$SITE/post/$postId">"""), html)
    }
}
