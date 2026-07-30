package com.example.linksphere.domain.interaction

import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime
import java.util.UUID

data class BookmarkFolderItemId(
    val userId: UUID = UUID(0, 0),
    val postId: UUID = UUID(0, 0),
    val folderId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(
    name = "bookmark_folder_items",
    indexes = [
        Index(name = "idx_bookmark_folder_items_folder", columnList = "folder_id, post_id"),
    ],
)
@IdClass(BookmarkFolderItemId::class)
class TableBookmarkFolderItem(
    @Id @Column(name = "user_id", nullable = false) val userId: UUID,
    @Id @Column(name = "post_id", nullable = false) val postId: UUID,
    @Id @Column(name = "folder_id", nullable = false) val folderId: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime = LocalDateTime.now(),
)
