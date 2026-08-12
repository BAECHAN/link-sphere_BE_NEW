package com.example.linksphere.domain.post

import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime
import java.util.UUID

data class PostViewId(
    val userId: UUID = UUID(0, 0),
    val postId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(
    name = "post_views",
    indexes = [
        Index(name = "idx_post_views_user_viewed", columnList = "user_id, viewed_at"),
    ],
)
@IdClass(PostViewId::class)
class TablePostView(
    @Id @Column(name = "user_id", nullable = false) val userId: UUID,
    @Id @Column(name = "post_id", nullable = false) val postId: UUID,
    @Column(name = "viewed_at", nullable = false) var viewedAt: LocalDateTime = LocalDateTime.now(),
)
