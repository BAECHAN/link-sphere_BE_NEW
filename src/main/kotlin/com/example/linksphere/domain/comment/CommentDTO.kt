package com.example.linksphere.domain.comment

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
    val postId: UUID,
    val userId: UUID,
    val content: String,
    val isDeleted: Boolean,
    val author: CommentAuthor,
    var replies: List<CommentResponse> = emptyList(),
    // Additional field to indicate if it's a reaction target
    val likeCount: Int = 0,
    val isLiked: Boolean = false,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val linkMetadata: LinkMetadata? = null,
)

data class CommentAuthor(val id: UUID, val nickname: String, val image: String?)

data class CreateCommentRequest(
    val content: String? = null,
    val images: List<String>? = null,
)

// 댓글 생성/수정 커밋 후 발행되는 후처리 이벤트 (알림 발송 + 링크 프리뷰 크롤링).
// commentId만 싣고 나머지는 처리 시점에 DB에서 다시 읽는다 - stale 데이터 방지.
data class CommentPostProcessEvent(
    val commentId: UUID,
    val notify: Boolean,
)
