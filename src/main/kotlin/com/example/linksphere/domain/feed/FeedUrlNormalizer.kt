package com.example.linksphere.domain.feed

import java.net.URI

/**
 * 피드 항목 URL을 "중복 판정 키"로만 정규화한다.
 *
 * 결과값은 feed_items.normalized_url(dedupe 키)에만 쓰고, 실제 posts.url 저장과
 * 크롤링 요청에는 원본 URL을 그대로 쓴다 — 그래서 여기서는 공격적으로 정규화해도 안전하다.
 * 파싱에 실패하면 예외를 던지지 않고 원본 문자열을 그대로 돌려준다(dedupe만 못할 뿐, 수집 자체는 막지 않는다).
 */
object FeedUrlNormalizer {

    private val TRACKING_PARAMS = setOf("fbclid", "gclid")

    fun normalize(url: String): String = runCatching {
        val uri = URI(url.trim())
        val host = (uri.host ?: return url).lowercase().removePrefix("www.")

        val query =
            (uri.rawQuery ?: "")
                .split("&")
                .filter { it.isNotEmpty() }
                .map { it.split("=", limit = 2) }
                .filter { pair ->
                    val name = pair[0].lowercase()
                    name !in TRACKING_PARAMS && !name.startsWith("utm_")
                }
                .sortedBy { it[0] }
                .joinToString("&") { it.joinToString("=") }

        var path = uri.rawPath ?: ""
        if (path.length > 1 && path.endsWith("/")) path = path.removeSuffix("/")

        buildString {
            append("https://")
            append(host)
            append(path)
            if (query.isNotEmpty()) {
                append("?")
                append(query)
            }
        }
    }.getOrDefault(url)
}
