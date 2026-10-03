package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryResponse
import org.springframework.data.domain.Page
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
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val last: Boolean,
    // 한/영 자판 미스매칭 보정으로 재검색했을 때만 채워진다 (예: spdlqj -> 네이버)
    val correctedSearch: String? = null,
) {
    companion object {
        fun from(
            page: Page<TablePost>,
            postResponses: List<PostResponse>,
            correctedSearch: String? = null,
        ): PostPageResponse = PostPageResponse(
            content = postResponses,
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages,
            last = page.isLast,
            correctedSearch = correctedSearch,
        )
    }
}

data class PostCreatedEvent(
    val postId: UUID,
    val userId: UUID,
    val title: String,
    val description: String?,
    val content: String,
    val existingTags: List<String>,
)
