package com.example.linksphere.domain.comment

import org.springframework.data.domain.Page
import java.time.LocalDateTime
import java.util.UUID

data class LinkMetadata(
    val url: String,
    val title: String,
    val description: String?,
    val ogImage: String?,
)

data class CommentResponse(
    val id: UUID,
    val content: String,
    val isDeleted: Boolean,
    val author: CommentAuthor,
    var replies: List<CommentResponse> = emptyList(),
    // Additional field to indicate if it's a reaction target
    val likeCount: Int = 0,
    val isLiked: Boolean = false,
    val createdAt: LocalDateTime,
    val linkMetadata: LinkMetadata? = null,
)

data class CommentAuthor(val id: UUID, val nickname: String, val image: String?)

data class CreateCommentRequest(
    val content: String? = null,
    val images: List<String>? = null,
)

// "내 댓글" 목록 전용 응답. 기존 CommentResponse에 postId/postTitle을 얹지 않는 이유:
// CommentResponse는 GET /post/{postId}/comment 응답 계약이라 필드를 더하면 그 계약까지 바뀐다.
data class MyCommentResponse(
    val id: UUID,
    val content: String,
    val createdAt: LocalDateTime,
    val postId: UUID,
    val postTitle: String,
)

data class MyCommentPageResponse(
    val content: List<MyCommentResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val last: Boolean,
) {
    companion object {
        fun from(page: Page<TableComment>): MyCommentPageResponse = MyCommentPageResponse(
            content = page.content.map {
                MyCommentResponse(
                    id = it.id,
                    content = it.content,
                    createdAt = it.createdAt,
                    postId = it.postId,
                    // join fetch로 가져온 post - 가시성 게이트 자체가 post 존재를 전제하므로
                    // null이면 안 되지만, 연관관계 타입 자체는 nullable이라 방어적으로 폴백한다.
                    postTitle = it.post?.title ?: "",
                )
            },
            page = page.number,
            size = page.size,
            totalElements = page.totalElements,
            totalPages = page.totalPages,
            last = page.isLast,
        )
    }
}

// 댓글 생성/수정 커밋 후 발행되는 후처리 이벤트 (알림 발송 + 링크 프리뷰 크롤링).
// commentId만 싣고 나머지는 처리 시점에 DB에서 다시 읽는다 - stale 데이터 방지.
data class CommentPostProcessEvent(
    val commentId: UUID,
    val notify: Boolean,
)
