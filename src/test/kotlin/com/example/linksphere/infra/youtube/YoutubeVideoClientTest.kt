package com.example.linksphere.infra.youtube

import com.example.linksphere.infra.youtube.dto.YoutubeRawSnippet
import com.example.linksphere.infra.youtube.dto.YoutubeThumbnail
import com.example.linksphere.infra.youtube.dto.YoutubeThumbnails
import com.example.linksphere.infra.youtube.dto.YoutubeVideoItem
import com.example.linksphere.infra.youtube.dto.YoutubeVideosResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class YoutubeVideoClientTest {

    private val client = YoutubeVideoClient(apiKey = "test-key")

    @Test
    fun `extractVideoId는 watch URL에서 v 파라미터를 뽑는다`() {
        assertEquals("dQw4w9WgXcQ", client.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `extractVideoId는 list와 t 파라미터가 섞여도 v만 뽑는다`() {
        assertEquals(
            "dQw4w9WgXcQ",
            client.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc&t=42s"),
        )
    }

    @Test
    fun `extractVideoId는 youtu_be 짧은 링크에서 뽑고 si 파라미터를 무시한다`() {
        assertEquals("dQw4w9WgXcQ", client.extractVideoId("https://youtu.be/dQw4w9WgXcQ?si=AbCdEf123"))
    }

    @Test
    fun `extractVideoId는 shorts 경로를 지원한다`() {
        assertEquals("dQw4w9WgXcQ", client.extractVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
    }

    @Test
    fun `extractVideoId는 embed 경로를 지원한다`() {
        assertEquals("dQw4w9WgXcQ", client.extractVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
    }

    @Test
    fun `extractVideoId는 live 경로를 지원한다`() {
        assertEquals("dQw4w9WgXcQ", client.extractVideoId("https://www.youtube.com/live/dQw4w9WgXcQ"))
    }

    @Test
    fun `extractVideoId는 m과 music 서브도메인을 지원한다`() {
        assertEquals("dQw4w9WgXcQ", client.extractVideoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", client.extractVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `extractVideoId는 playlist URL이면 null이다`() {
        assertNull(client.extractVideoId("https://www.youtube.com/playlist?list=PLabc"))
    }

    @Test
    fun `extractVideoId는 handle URL이면 null이다`() {
        assertNull(client.extractVideoId("https://www.youtube.com/@handle"))
    }

    @Test
    fun `extractVideoId는 11자가 아니면 null이다`() {
        assertNull(client.extractVideoId("https://www.youtube.com/watch?v=short"))
    }

    @Test
    fun `parseSnippet은 정상 응답을 그대로 매핑한다`() {
        val response =
            YoutubeVideosResponse(
                items = listOf(
                    YoutubeVideoItem(
                        snippet = YoutubeRawSnippet(
                            title = "영상 제목",
                            description = "영상 설명",
                            thumbnails = YoutubeThumbnails(
                                high = YoutubeThumbnail("https://i.ytimg.com/vi/abc/hqdefault.jpg"),
                                medium = null,
                                default = null,
                            ),
                        ),
                    ),
                ),
            )

        val snippet = client.parseSnippet(response)

        assertEquals("영상 제목", snippet!!.title)
        assertEquals("영상 설명", snippet.description)
        assertEquals("https://i.ytimg.com/vi/abc/hqdefault.jpg", snippet.thumbnailUrl)
    }

    @Test
    fun `parseSnippet은 items가 빈 배열이면 null이다`() {
        assertNull(client.parseSnippet(YoutubeVideosResponse(items = emptyList())))
    }

    @Test
    fun `parseSnippet은 items가 null이면 null이다`() {
        assertNull(client.parseSnippet(YoutubeVideosResponse(items = null)))
    }

    @Test
    fun `parseSnippet은 high가 없으면 medium으로, medium도 없으면 default로 내려간다`() {
        val medium =
            client.parseSnippet(
                YoutubeVideosResponse(
                    items = listOf(
                        YoutubeVideoItem(
                            YoutubeRawSnippet(
                                title = null,
                                description = null,
                                thumbnails = YoutubeThumbnails(
                                    high = null,
                                    medium = YoutubeThumbnail("medium-url"),
                                    default = YoutubeThumbnail("default-url"),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        assertEquals("medium-url", medium!!.thumbnailUrl)

        val default =
            client.parseSnippet(
                YoutubeVideosResponse(
                    items = listOf(
                        YoutubeVideoItem(
                            YoutubeRawSnippet(
                                title = null,
                                description = null,
                                thumbnails = YoutubeThumbnails(high = null, medium = null, default = YoutubeThumbnail("default-url")),
                            ),
                        ),
                    ),
                ),
            )
        assertEquals("default-url", default!!.thumbnailUrl)
    }

    @Test
    fun `fetchSnippet은 키가 비어있으면 HTTP 없이 null이다`() {
        val blankKeyClient = YoutubeVideoClient(apiKey = "")

        assertNull(blankKeyClient.fetchSnippet("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `fetchSnippet은 videoId를 못 뽑으면 HTTP 없이 null이다`() {
        assertNull(client.fetchSnippet("https://www.youtube.com/@handle"))
    }
}
