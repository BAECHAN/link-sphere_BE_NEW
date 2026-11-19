package com.example.linksphere.domain.post

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.util.HtmlUtils
import java.util.UUID

private const val SITE_NAME = "Link Sphere"

// FE index.html의 <meta name="description">과 같은 문장이다 - 사람이 여는 앱 HTML과 봇이 받는 기본값이 같은 소개를 쓴다
private const val SITE_DESCRIPTION = "유용한 아티클과 리소스 링크를 공유하고, 다른 사람이 모은 링크를 발견하는 소셜 플랫폼이에요."

// FE 라우트 `/post/:id`의 id 자리와 같은 정식 UUID만이다. UUID.fromString은 "1-1-1-1-1"처럼 자릿수가 모자란 문자열도 받는다
private val CANONICAL_UUID = Regex("""^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$""")

/**
 * 메신저 미리보기 봇(카카오톡·Slack 등)에 줄 글 주소의 OG 태그 HTML을 만든다. 글 주소는 어떤 글이든 같은 SPA 껍데기라 봇이 글마다
 * 다른 미리보기를 만들지 못한다 - CloudFront Function이 봇 요청만 이 HTML로 보낸다(docs/plans/2026-11-19-post-link-in-app-and-og.md
 * Phase 2).
 *
 * 봇은 토큰을 보내지 않으니 항상 비로그인으로 묻는다 - 글쓴이 본인의 비공개 글도 기본값이 나온다. 볼 수 없는 글·지운 글·없는 글은
 * 구분하지 않고 사이트 이름·소개만 싣는다(이미지 없음, 2026-11-19 사용자 결정).
 */
@Service
class PostOgService(
    private val postLookupService: PostLookupService,
    // 메일 링크·우리 글 주소 판정과 같은 설정이다(AuthService, PostLinkParser). og:url을 글 주소로 두어 봇이 같은 글로 묶게 한다
    @Value("\${app.frontend.url:}") frontendUrl: String,
) {

    private val siteUrl = frontendUrl.trimEnd('/')

    fun renderPostHtml(postId: String): String {
        val id = postId.takeIf { CANONICAL_UUID.matches(it) }?.let(UUID::fromString)
        val preview = id?.let { postLookupService.findVisiblePreviews(listOf(it), null)[it] }

        if (id == null || preview == null) {
            return render(
                title = SITE_NAME,
                description = SITE_DESCRIPTION,
                image = null,
                url = id?.let { "$siteUrl/post/$it" } ?: siteUrl,
                type = "website",
            )
        }

        return render(
            title = preview.title,
            description = preview.description?.takeIf { it.isNotBlank() },
            image = preview.ogImage?.takeIf { it.isNotBlank() },
            url = "$siteUrl/post/$id",
            type = "article",
        )
    }

    private fun render(title: String, description: String?, image: String?, url: String, type: String): String {
        val meta =
            listOfNotNull(
                description?.let { """<meta name="description" content="${escape(it)}">""" },
                """<meta property="og:site_name" content="$SITE_NAME">""",
                """<meta property="og:type" content="$type">""",
                """<meta property="og:title" content="${escape(title)}">""",
                description?.let { """<meta property="og:description" content="${escape(it)}">""" },
                url.takeIf { it.isNotEmpty() }?.let { """<meta property="og:url" content="${escape(it)}">""" },
                image?.let { """<meta property="og:image" content="${escape(it)}">""" },
                // 이미지가 있으면 큰 카드, 없으면 작은 카드다 - 큰 카드에 이미지가 없으면 빈 자리가 생긴다
                """<meta name="twitter:card" content="${if (image != null) "summary_large_image" else "summary"}">""",
            )

        return buildString {
            appendLine("<!doctype html>")
            appendLine("""<html lang="ko">""")
            appendLine("<head>")
            appendLine("""<meta charset="utf-8">""")
            appendLine("<title>${escape(title)}</title>")
            meta.forEach(::appendLine)
            appendLine("</head>")
            appendLine("<body></body>")
            appendLine("</html>")
        }
    }

    // 제목·설명·썸네일 주소는 사용자가 넣은 값이다 - 따옴표·꺾쇠·앰퍼샌드를 엔티티로 바꿔 속성 밖으로 나가지 못하게 한다
    private fun escape(value: String): String = HtmlUtils.htmlEscape(value, Charsets.UTF_8.name())
}
