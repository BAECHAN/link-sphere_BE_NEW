package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.TableCategory
import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class AiStatus {
        NONE,
        PENDING,
        COMPLETED,
        FAILED
}

@Entity
@Table(name = "posts")
class TablePost(
        @Id
        @GeneratedValue(strategy = GenerationType.AUTO)
        @Column(name = "id", nullable = false)
        val id: UUID? = null,
        @Column(name = "user_id", nullable = false) val userId: UUID,
        @Column(name = "url", nullable = false, columnDefinition = "text") val url: String,
        @Column(name = "title", nullable = false, columnDefinition = "text") var title: String,
        @Column(name = "description", columnDefinition = "text") val description: String? = null,
        @Column(name = "tags") @JdbcTypeCode(SqlTypes.ARRAY) var tags: List<String>? = null,
        @ManyToMany(fetch = FetchType.LAZY)
        @JoinTable(
                name = "post_categories",
                joinColumns = [JoinColumn(name = "post_id")],
                inverseJoinColumns = [JoinColumn(name = "category_id")]
        )
        val categories: MutableSet<TableCategory> = mutableSetOf(),
        @Column(name = "og_image", columnDefinition = "text") val ogImage: String? = null,
        @Column(name = "ai_summary", columnDefinition = "text") var aiSummary: String? = null,
        @Column(name = "view_count") val viewCount: Int? = 0,
        @Column(name = "created_at") val createdAt: LocalDateTime? = LocalDateTime.now(),
        @Enumerated(EnumType.STRING)
        @Column(name = "ai_status")
        var aiStatus: AiStatus = AiStatus.NONE,
        @Column(name = "is_private", nullable = false) var isPrivate: Boolean = false
)
