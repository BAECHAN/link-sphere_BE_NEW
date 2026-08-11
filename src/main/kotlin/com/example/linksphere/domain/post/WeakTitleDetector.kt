package com.example.linksphere.domain.post

/**
 * 크롤링으로 얻은 제목이 "쓸 만한 제목"인지 판정한다.
 *
 * UrlMetadataExtractor는 og:title → <title> → URL 문자열 순으로 폴백하므로,
 * OG 태그와 <title>이 모두 비는 페이지에서는 URL이 그대로 제목이 된다.
 * PostAIService가 AI 생성 제목으로 대체할지 결정하는 게이트로만 쓴다.
 */
object WeakTitleDetector {

    private const val MIN_MEANINGFUL_LENGTH = 3

    fun isWeak(title: String, url: String): Boolean {
        val t = title.trim()
        if (t.length < MIN_MEANINGFUL_LENGTH) return true
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return true
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: return false
        // 사이트명만 제목인 경우 (example.com / Example)
        return t.equals(host, ignoreCase = true) || t.equals(host.substringBefore("."), ignoreCase = true)
    }
}
