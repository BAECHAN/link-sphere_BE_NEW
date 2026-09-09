package com.example.linksphere.infra.youtube.dto

// videos.list(part=snippet) 원본 응답 형태. 여기 선언 안 한 필드(channelTitle 등)는
// RestClient 기본 Jackson 설정이 조용히 무시한다 - GeminiResponse가 usageMetadata를
// 선언 안 하고도 도는 것과 같은 이유.
data class YoutubeVideosResponse(val items: List<YoutubeVideoItem>?)

data class YoutubeVideoItem(val snippet: YoutubeRawSnippet?)

data class YoutubeRawSnippet(
    val title: String?,
    val description: String?,
    val thumbnails: YoutubeThumbnails?,
)

data class YoutubeThumbnails(
    val high: YoutubeThumbnail?,
    val medium: YoutubeThumbnail?,
    val default: YoutubeThumbnail?,
)

data class YoutubeThumbnail(val url: String?)

/**
 * YoutubeVideoClient가 실제로 돌려주는 평탄화된 형태 - 썸네일 티어 선택(high → medium →
 * default)까지 끝난 상태다. UrlMetadataExtractor는 원본 JSON 구조를 몰라도 된다.
 */
data class YoutubeSnippet(
    val title: String?,
    val description: String?,
    val thumbnailUrl: String?,
)
