package com.example.linksphere.domain.post

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
    // 카테고리는 id로만 든다 - 이름·slug는 category 도메인에 묻는다(CategoryService). 테이블은 연결 테이블 그대로다.
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "post_categories", joinColumns = [JoinColumn(name = "post_id")])
    @Column(name = "category_id", nullable = false)
    val categoryIds: MutableSet<Long> = mutableSetOf(),
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
    // 브라우저가 만든 Idempotency-Key. 봇과 이 컬럼 이전의 글은 null이다.
    @Column(name = "idempotency_key", updatable = false) val idempotencyKey: UUID? = null,
    // 피드 카드용 사본(960px)의 절대 주소. null이면 사본이 없어 FE가 ogImage를 그린다. 쓰기는 PostRepository의 네이티브
    // UPDATE로만 한다(사본 잡의 조건부 채우기, og_image가 바뀔 때 비우기) - embedding과 같은 이유로 insertable/updatable=false다:
    // AI 잡처럼 글을 읽어 전체를 save하는 경로가 그 사이 사본 잡이 채운 값을 옛 값(null)으로 덮지 않는다. 메모리 값은 응답용이다.
    @Column(name = "og_image_thumbnail", columnDefinition = "text", insertable = false, updatable = false)
    var ogImageThumbnail: String? = null,
)
