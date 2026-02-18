package com.example.linksphere.domain.comment

import java.time.LocalDateTime
import java.util.UUID

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
)

data class CommentAuthor(val id: UUID, val nickname: String, val image: String?)

data class CreateCommentRequest(val content: String)
