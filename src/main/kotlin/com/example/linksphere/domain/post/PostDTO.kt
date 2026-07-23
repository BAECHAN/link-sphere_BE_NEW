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
)

data class PostVisibilityUpdateRequest(val isPrivate: Boolean)

data class PostUpdateRequest(
    // null이면 URL 변경 없음으로 취급한다 (url 필드를 보내지 않는 구버전 클라이언트 호환).
    val url: String? = null,
    // 비워두면 새 링크에서 가져온 제목을 쓴다 (URL 변경 없으면 기존 제목 유지).
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
    val bookmarkFolderId: UUID? = null,
)

data class PostResponse(
    val id: UUID,
    val userId: UUID,
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
)

data class PostPageResponse(
    val content: List<PostResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val last: Boolean,
) {
    companion object {
        fun from(page: Page<TablePost>, postResponses: List<PostResponse>): PostPageResponse = PostPageResponse(
            content = postResponses,
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages,
            last = page.isLast,
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
