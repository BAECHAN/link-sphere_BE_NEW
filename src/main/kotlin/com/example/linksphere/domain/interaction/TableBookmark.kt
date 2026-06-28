package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.TablePost
import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime
import java.util.UUID

data class BookmarkId(val userId: UUID = UUID(0, 0), val postId: UUID = UUID(0, 0)) :
        Serializable

@Entity
@Table(
        name = "bookmarks",
        indexes =
                [
                        Index(
                                name = "idx_bookmark_user_post",
                                columnList = "user_id, post_id",
                                unique = true
                        ),
                        Index(name = "idx_bookmark_post_user", columnList = "post_id, user_id")]
)
@IdClass(BookmarkId::class)
class TableBookmark(
        @Id @Column(name = "user_id", nullable = false) val userId: UUID,
        @Id @Column(name = "post_id", nullable = false) val postId: UUID,
        @Column(name = "folder_id") var folderId: UUID? = null,
        @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "post_id", insertable = false, updatable = false)
        val post: TablePost? = null
)
