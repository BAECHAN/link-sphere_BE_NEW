package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryResponse
import java.time.LocalDateTime
import java.util.UUID

data class PostCreateRequest(
    val url: String,
    val title: String? = null,
    val categoryIds: List<Long>? = emptyList(),
    val isPrivate: Boolean = false,
    // 폴더 없이 북마크만(미분류)
    val bookmark: Boolean = false,
    // 소속시킬 폴더들
    val folderIds: List<UUID>? = null,
)

data class PostVisibilityUpdateRequest(val isPrivate: Boolean)

data class PostUpdateRequest(
    // null이면 URL 변경 없음으로 취급한다 (url 필드를 보내지 않는 구버전 클라이언트 호환).
    val url: String? = null,
    // 비워두면 URL 변경 여부와 무관하게 링크를 다시 크롤링해 제목을 채운다.
    // 재수집 제목이 빈약하면(WeakTitleDetector) 기존 제목을 유지한다.
    val title: String? = null,
    val categoryIds: List<Long>? = emptyList(),
    val isPrivate: Boolean = false,
)

data class UserSummary(val id: UUID, val nickname: String?, val image: String?)

data class PostStats(
    val viewCount: Int,
    val likeCount: Int,
    val commentCount: Int,
    val bookmarkCount: Int,
)

data class PostLikeResponse(
    val isLiked: Boolean,
    val likeCount: Int,
)

/** 조회 기록(POST /post/{id}/view) 응답. viewCount는 이번 조회를 셀지 판단한 뒤의 조회수다. */
data class PostViewRecordResponse(val viewCount: Int)

data class PostUserInteractions(
    val isLiked: Boolean,
    val isBookmarked: Boolean,
    val bookmarkFolderIds: List<UUID> = emptyList(),
)

/** 작성 중 링크 미리보기 응답. 크롤링 본문(pageContent)은 AI 재료라 싣지 않는다. */
data class LinkPreviewResponse(
    val url: String,
    val title: String,
    val description: String?,
    val ogImage: String?,
)

data class PostResponse(
    val id: UUID,
    val url: String,
    val title: String,
    val description: String?,
    val tags: List<String>?,
    val categories: List<CategoryResponse>,
    val ogImage: String?,
    // 피드 카드용으로 줄인 사본(가로 960px 안팎)의 절대 주소. null이면 사본이 아직 없거나 만들 수 없는 원본이라 ogImage를 그린다.
    // 상세 화면·공유 미리보기는 계속 ogImage를 쓴다(docs/plans/2026-11-25-og-thumbnail.md "쓰는 곳")
    val ogImageThumbnail: String?,
    val aiSummary: String?,
    val createdAt: LocalDateTime?,
    val aiStatus: AiStatus,
    val isPrivate: Boolean,
    val stats: PostStats,
    val userInteractions: PostUserInteractions,
    val author: UserSummary,
    // 검색 결과 배지("의미로 찾았어요")용 - 검색어가 있고 이 글이 키워드로는 안 걸렸지만
    // 의미 검색으로 걸렸을 때만 true. 검색이 없거나 키워드로 걸린 결과는 항상 false.
    val isSemanticMatch: Boolean = false,
)

data class PostPageResponse(
    val content: List<PostResponse>,
    // 다음 쪽을 읽을 cursor. null이면 마지막 쪽이다.
    val nextCursor: String? = null,
    // 한/영 자판 미스매칭 보정으로 재검색했을 때만 채워진다 (예: spdlqj -> 네이버)
    val correctedSearch: String? = null,
    // filter=isLiked가 최근 좋아요 일부만 보고 걸렀을 때 그 개수(10000). 좋아요가 그 이하라 전부 봤거나 필터가 없으면 null이다
    val likedFilterLimit: Int? = null,
)

data class PostCreatedEvent(
    val postId: UUID,
    val userId: UUID,
    val title: String,
    val description: String?,
    val content: String,
    val existingTags: List<String>,
)

// 글의 og_image가 바뀌었다(등록·주소 변경·빈 og:image 채움·삭제). OgThumbnailService가 커밋 뒤 듣고 옛 사본을 지운 뒤 새 사본 잡을
// 맡긴다. 삭제면 ogImage가 null, 새 글이면 previousOgImage가 null이다
data class PostOgImageChangedEvent(
    val postId: UUID,
    val previousOgImage: String?,
    val ogImage: String?,
)

// 잡 og-thumbnail이 받는 내용. ogImage는 맡길 때의 원본 주소다 - 저장 직전에 글의 og_image와 비교한다
data class OgThumbnailJobEvent(
    val postId: UUID,
    val ogImage: String,
)
