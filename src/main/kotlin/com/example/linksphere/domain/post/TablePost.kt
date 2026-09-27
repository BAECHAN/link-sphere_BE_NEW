package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.TableCategory
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

enum class AiStatus {
    NONE,
    PENDING,
    COMPLETED,
    FAILED,
}

@Entity
@Table(name = "posts")
class TablePost(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false)
    val id: UUID? = null,
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "url", nullable = false, columnDefinition = "text") var url: String,
    @Column(name = "title", nullable = false, columnDefinition = "text") var title: String,
    @Column(name = "description", columnDefinition = "text") var description: String? = null,
    @Column(name = "tags") @JdbcTypeCode(SqlTypes.ARRAY) var tags: List<String>? = null,
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "post_categories",
        joinColumns = [JoinColumn(name = "post_id")],
        inverseJoinColumns = [JoinColumn(name = "category_id")],
    )
    val categories: MutableSet<TableCategory> = mutableSetOf(),
    @Column(name = "og_image", columnDefinition = "text") var ogImage: String? = null,
    @Column(name = "ai_summary", columnDefinition = "text") var aiSummary: String? = null,
    @Column(name = "view_count") val viewCount: Int? = 0,
    @Column(name = "created_at") val createdAt: LocalDateTime? = LocalDateTime.now(),
    @Enumerated(EnumType.STRING)
    @Column(name = "ai_status")
    var aiStatus: AiStatus = AiStatus.NONE,
    @Column(name = "is_private", nullable = false) var isPrivate: Boolean = false,
    // 검색 의미 매칭용 임베딩(pgvector). 쓰기는 항상 PostRepository.updateEmbedding()의
    // 네이티브 UPDATE로만 한다 - insertable/updatable=false로 막아, AI 잡이 막 써넣은
    // 새 임베딩을 그 사이 다른 경로의 전체 save()가 옛 값(또는 null)으로 덮어쓰는 경쟁을
    // 방지한다.
    @Column(name = "embedding", insertable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.VECTOR)
    val embedding: FloatArray? = null,
)
