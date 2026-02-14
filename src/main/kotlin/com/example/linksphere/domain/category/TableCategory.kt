package com.example.linksphere.domain.category

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "categories")
class TableCategory(
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id", nullable = false)
        val id: Long? = null,
        @Column(name = "name", nullable = false, unique = true, length = 100) val name: String,
        @Column(name = "slug", nullable = false, unique = true, length = 100) val slug: String,
        @Column(name = "sort_order", nullable = false) val sortOrder: Int = 0,
        @Column(name = "created_at") val createdAt: LocalDateTime? = LocalDateTime.now()
)
