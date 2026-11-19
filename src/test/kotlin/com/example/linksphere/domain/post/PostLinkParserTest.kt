package com.example.linksphere.domain.post

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

private const val POST_ID = "0f2a6c1e-58b3-4d7a-9c41-6e0d2b7f3a95"

// 댓글의 링크 카드가 "우리 글 주소"로 볼 주소의 범위다(docs/plans/2026-11-19-comment-post-link-preview.md "우리 글 주소 판정").
// 여기서 id가 나오는 주소만 크롤링을 건너뛰고 글에서 직접 읽는다.
class PostLinkParserTest {

    private val parser = PostLinkParser("https://linksphere.click")

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://linksphere.click/post/$POST_ID",
            "https://www.linksphere.click/post/$POST_ID",
            "http://linksphere.click/post/$POST_ID",
            "https://LinkSphere.click/post/$POST_ID",
            "https://linksphere.click/post/$POST_ID/",
            "https://linksphere.click/post/$POST_ID?ref=share",
            "https://linksphere.click/post/$POST_ID#comment-3b9d1f6a-2c47-4e85-b0a3-7d5e9c1f4a28",
        ],
    )
    fun `우리 글 상세 주소면 글 id를 돌려준다`(url: String) {
        assertEquals(UUID.fromString(POST_ID), parser.postIdOf(url))
    }

    // FE 라우트 `/post/:id`의 id 자리는 대소문자를 가리지 않는 UUID다
    @Test
    fun `id가 대문자로 적혀 있어도 같은 글이다`() {
        assertEquals(UUID.fromString(POST_ID), parser.postIdOf("https://linksphere.click/post/${POST_ID.uppercase()}"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // 다른 사이트, 우리 이름을 흉내 낸 호스트
            "https://example.com/post/$POST_ID",
            "https://linksphere.click.example.com/post/$POST_ID",
            "https://notlinksphere.click/post/$POST_ID",
            "https://linksphere.click@example.com/post/$POST_ID",
            // 글 상세가 아닌 우리 주소
            "https://linksphere.click/",
            "https://linksphere.click/bookmark",
            "https://linksphere.click/post/submit",
            "https://linksphere.click/post/edit/$POST_ID",
            "https://linksphere.click/post/$POST_ID/extra",
            "https://linksphere.click/api/post/$POST_ID",
            // id 자리가 UUID가 아니다 - 문장 끝 마침표가 주소에 붙은 경우도 여기에 든다
            "https://linksphere.click/post/123",
            "https://linksphere.click/post/$POST_ID.",
            // 주소로 읽을 수 없다
            "https://linksphere.click/post/$POST_ID|x",
            "not a url",
        ],
    )
    fun `우리 글 상세 주소가 아니면 null이다`(url: String) {
        assertNull(parser.postIdOf(url))
    }

    // 설정이 없으면 어떤 주소도 우리 글로 보지 않는다 - 전부 지금처럼 크롤링한다
    @Test
    fun `프런트 주소 설정이 비어 있으면 항상 null이다`() {
        assertNull(PostLinkParser("").postIdOf("https://linksphere.click/post/$POST_ID"))
    }

    @Test
    fun `프런트 주소 설정에 www가 붙어 있어도 www 없는 주소와 같은 사이트로 본다`() {
        assertEquals(UUID.fromString(POST_ID), PostLinkParser("https://www.linksphere.click").postIdOf("https://linksphere.click/post/$POST_ID"))
    }
}
