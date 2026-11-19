package com.example.linksphere.domain.post

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.util.UUID

// FE 라우트 `/post/:id`의 글 상세만이다. `/post/submit`·`/post/edit/<id>`처럼 id 자리가 UUID가 아니거나 뒤에 경로가 더 붙으면
// 글 상세가 아니다. 끝 `/` 하나는 같은 화면으로 본다
private val POST_DETAIL_PATH = Regex("""^/post/([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})/?$""")

/**
 * 주소가 이 서비스의 글 상세(FE 라우트 `/post/:id`)를 가리키는지 읽는다. 댓글의 링크 카드가 우리 글 주소를 크롤링하지 않고 글에서
 * 직접 읽으려고 쓴다(docs/plans/2026-11-19-comment-post-link-preview.md) - 우리 글 주소는 어떤 글이든 같은 SPA 껍데기 HTML을
 * 돌려줘, 크롤링하면 제목이 전부 "Link Sphere"가 되고 썸네일이 없다.
 */
@Component
class PostLinkParser(@Value("\${app.frontend.url:}") frontendUrl: String) {

    // 메일 링크를 만들 때 쓰는 설정과 같은 값이다(AuthService). 비었거나 주소로 읽을 수 없으면 null이고, 그때는 어떤 주소도
    // 우리 글로 보지 않는다 - 전부 지금처럼 크롤링한다
    private val frontendHost: String? = runCatching { URI(frontendUrl) }.getOrNull()?.let(::siteHostOf)

    /** 우리 글 상세 주소면 글 id, 아니면 null이다. 쿼리와 `#comment-<id>` 같은 조각은 보지 않는다. */
    fun postIdOf(url: String): UUID? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null

        if (frontendHost == null || siteHostOf(uri) != frontendHost) {
            return null
        }

        return POST_DETAIL_PATH.matchEntire(uri.rawPath.orEmpty())?.let { UUID.fromString(it.groupValues[1]) }
    }

    // 호스트는 대소문자를 가리지 않고, `www.`가 붙은 주소도 같은 사이트다(CORS 허용 목록에 둘 다 있다)
    private fun siteHostOf(uri: URI): String? = uri.host?.lowercase()?.removePrefix("www.")
}
