package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryResponse
import java.time.LocalDateTime
import java.util.UUID
import org.springframework.data.domain.Page

data class PostCreateRequest(
        val url: String,
        val categoryIds: List<Long>? = emptyList(),
        val isPrivate: Boolean = false
)

data class PostVisibilityUpdateRequest(val isPrivate: Boolean)

data class UserSummary(val id: UUID, val nickname: String?, val image: String?)

data class PostStats(
        val viewCount: Int,
        val likeCount: Int,
        val commentCount: Int,
        val bookmarkCount: Int
)

data class PostUserInteractions(val isLiked: Boolean, val isBookmarked: Boolean)

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
        val author: UserSummary
)

data class PostPageResponse(
        val content: List<PostResponse>,
        val page: Int,
        val size: Int,
        val totalElements: Long,
        val totalPages: Int,
        val last: Boolean
) {
    companion object {
        fun from(page: Page<TablePost>, postResponses: List<PostResponse>): PostPageResponse {
            return PostPageResponse(
                    content = postResponses,
                    page = page.number,
                    size = page.size,
                    totalElements = page.totalElements,
                    totalPages = page.totalPages,
                    last = page.isLast
            )
        }
    }
}

data class PostCreatedEvent(
        val postId: UUID,
        val userId: UUID,
        val title: String,
        val description: String?,
        val content: String,
        val existingTags: List<String>
)
