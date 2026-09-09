package com.example.linksphere.infra.youtube

import com.example.linksphere.infra.youtube.dto.YoutubeSnippet
import com.example.linksphere.infra.youtube.dto.YoutubeVideosResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

// watch/shorts/embed/live 경로 뒤에 오는 실제 videoId 형태. 이 검증을 통과한 값만 API에
// 보낸다 - (a) 쿼터를 헛되이 쓰지 않고 (b) 사용자 입력 문자열이 그대로 API URL에 보간되는
// 경로를 차단한다(호출 호스트가 googleapis.com 고정이라 SSRF 자체는 성립하지 않지만,
// 인젝션 방지 차원에서 형식을 못박는다).
private val VIDEO_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")

/**
 * YouTube Data API v3로 영상 설명(snippet.description)을 가져온다.
 *
 * 2026-09-08부터 YouTube가 Lambda(AWS) IP에 watch 페이지를 200 OK + 본문 94자짜리 빈
 * 셸로 내려주기 시작해, UrlMetadataExtractor의 기존 스크래핑(ytInitialPlayerResponse)이
 * 본문을 못 건지게 됐다. 이 클라이언트가 1순위 대안이다 - 자세한 경위는
 * docs/AI-ASYNC-PROCESSING.md §5.7 참고.
 */
@Component
class YoutubeVideoClient(
    // gemini.api.key(GeminiService.kt:18)와 달리 기본값을 둔다 - 키가 없어도 부팅은 되게
    // 하고(기존 스크래핑·oEmbed로 조용히 폴백), Lambda 환경변수를 코드 배포보다 늦게
    // 넣어도 애플리케이션이 죽지 않게 하기 위해서다.
    @Value("\${youtube.api.key:}") private val apiKey: String,
) {
    private val logger = LoggerFactory.getLogger(YoutubeVideoClient::class.java)

    // 임의 사이트를 상대하는 safeConnect(5s)·oEmbed(5s)보다 짧게 잡는다 - Google의
    // 메타데이터 GET이라 지연이 훨씬 안정적이다.
    private val requestFactory =
        JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build())
            .apply { setReadTimeout(Duration.ofSeconds(3)) }
    private val restClient = RestClient.builder().requestFactory(requestFactory).build()

    /**
     * 실패하면(키 없음·videoId 파싱 불가·쿼터 초과·삭제된 영상 등) 예외를 던지지 않고
     * null을 돌려준다 - 호출부(UrlMetadataExtractor)가 기존 스크래핑·oEmbed 경로로
     * 떨어지게 하기 위함이다(fetchYoutubeMetadata와 같은 태도).
     */
    fun fetchSnippet(url: String): YoutubeSnippet? {
        if (apiKey.isBlank()) return null
        val videoId = extractVideoId(url) ?: return null

        return try {
            val response =
                restClient
                    .get()
                    .uri(
                        "https://www.googleapis.com/youtube/v3/videos" +
                            "?part=snippet&id=$videoId" +
                            "&fields=items(snippet(title,description,thumbnails))" +
                            "&key=$apiKey",
                    )
                    .retrieve()
                    .body(YoutubeVideosResponse::class.java)

            parseSnippet(response)
        } catch (e: Exception) {
            // 키 무효(400)·API 미활성/쿼터 초과(403)·네트워크 타임아웃 등 무엇이든 여기로
            // 온다. 재시도할 대체 엔드포인트가 없고, 실패해도 기존 스크래핑·oEmbed로
            // 떨어지므로 예외를 위로 던지지 않는다.
            logger.warn("[YouTube API] videos.list 실패 - videoId: $videoId", e)
            null
        }
    }

    // 삭제·비공개 영상은 404가 아니라 200 + items:[] 로 온다.
    internal fun parseSnippet(response: YoutubeVideosResponse?): YoutubeSnippet? {
        val raw = response?.items?.firstOrNull()?.snippet ?: return null
        val thumbnailUrl = raw.thumbnails?.high?.url ?: raw.thumbnails?.medium?.url ?: raw.thumbnails?.default?.url

        return YoutubeSnippet(title = raw.title, description = raw.description, thumbnailUrl = thumbnailUrl)
    }

    /**
     * watch/youtu.be/shorts/embed/live 형태에서 videoId만 뽑는다. FeedUrlNormalizer와
     * 같은 이유로 정규식 한 방이 아니라 URI 파싱을 쓴다 - `?si=`·`&list=`·`&t=` 같은
     * 추적/부가 파라미터가 섞여도 깨지지 않는다. playlist·`@handle` 등 videoId가 없는
     * URL은 null.
     */
    internal fun extractVideoId(url: String): String? = runCatching {
        val uri = URI(url.trim())
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return@runCatching null

        val candidate =
            when {
                host == "youtu.be" -> uri.path?.trim('/')?.substringBefore("/")
                host == "youtube.com" || host.endsWith(".youtube.com") -> extractFromYoutubeComPath(uri)
                else -> null
            }

        candidate?.takeIf { VIDEO_ID_REGEX.matches(it) }
    }.getOrNull()

    private fun extractFromYoutubeComPath(uri: URI): String? {
        val path = uri.path ?: return null
        return when {
            path == "/watch" -> queryParam(uri, "v")
            path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore("/")
            path.startsWith("/embed/") -> path.removePrefix("/embed/").substringBefore("/")
            path.startsWith("/live/") -> path.removePrefix("/live/").substringBefore("/")
            else -> null
        }
    }

    private fun queryParam(uri: URI, name: String): String? = (uri.rawQuery ?: "")
        .split("&")
        .map { it.split("=", limit = 2) }
        .firstOrNull { it.getOrNull(0) == name }
        ?.getOrNull(1)
}
