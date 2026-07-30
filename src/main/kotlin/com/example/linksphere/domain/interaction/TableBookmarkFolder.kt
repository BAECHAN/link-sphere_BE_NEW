package com.example.linksphere.domain.interaction

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(
    name = "bookmark_folders",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_bookmark_folders_user_name",
            columnNames = ["user_id", "name"],
        ),
        // bookmark_folder_items 의 복합 FK (user_id, folder_id) 대상 — 소속의 소유자 == 폴더의 소유자를 DB가 강제
        UniqueConstraint(
            name = "uk_bookmark_folders_user_id",
            columnNames = ["user_id", "id"],
        ),
    ],
    indexes = [
        Index(
            name = "idx_bookmark_folders_user_sort",
            columnList = "user_id, sort_order",
        ),
    ],
)
class TableBookmarkFolder(
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "created_at", updatable = false, nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
