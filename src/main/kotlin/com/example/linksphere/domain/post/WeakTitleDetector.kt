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

    // 제목 템플릿("{제목} - {사이트명}")에서 실제로 쓰이는 구분자들.
    private const val SEPARATORS = "-–—|·:"

    fun isWeak(title: String, url: String): Boolean {
        // 제목 자리가 빈 채로 템플릿만 렌더되면 "- YouTube"처럼 구분자와 사이트명만 남는다.
        // 양 끝의 구분자만 떼고 판정한다 - 가운데 구분자는 왼쪽에 진짜 제목이 있다는 뜻이므로
        // 쪼개지 않는다("리액트 19 릴리즈 - React Blog"를 약한 제목으로 오판하지 않기 위함).
        val t = title.trim().trim { it in SEPARATORS }.trim()
        if (t.length < MIN_MEANINGFUL_LENGTH) return true
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return true
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: return false
        // 사이트명만 제목인 경우 (example.com / Example / "- YouTube"의 YouTube).
        // 영숫자만 남겨 비교하므로 youtu.be ≡ "YouTube"처럼 점이 낀 축약 도메인도 잡힌다.
        val normalized = alnum(t)
        if (normalized.isEmpty()) return true
        return normalized == alnum(host) || normalized == alnum(host.substringBefore("."))
    }

    private fun alnum(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
