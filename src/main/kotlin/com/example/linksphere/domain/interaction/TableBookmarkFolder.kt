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
                        columnNames = ["user_id", "name"]
                )
        ],
        indexes = [
                Index(
                        name = "idx_bookmark_folders_user_sort",
                        columnList = "user_id, sort_order"
                )
        ]
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
        var updatedAt: LocalDateTime = LocalDateTime.now()
)
