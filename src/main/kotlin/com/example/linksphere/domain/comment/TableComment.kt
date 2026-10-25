package com.example.linksphere.domain.comment

import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.domain.post.TablePost
import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "comments")
class TableComment(
    @Id @Column(name = "id", nullable = false) val id: UUID = UUID.randomUUID(),
    @Column(name = "post_id", nullable = false) val postId: UUID,
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "parent_id") val parentId: UUID? = null,
    @Column(name = "content", columnDefinition = "text", nullable = false) var content: String,
    @Column(name = "is_deleted", nullable = false) var isDeleted: Boolean = false,
    @Column(name = "link_url", columnDefinition = "text") var linkUrl: String? = null,
    @Column(name = "link_title", columnDefinition = "text") var linkTitle: String? = null,
    @Column(name = "link_description", columnDefinition = "text") var linkDescription: String? = null,
    @Column(name = "link_og_image", columnDefinition = "text") var linkOgImage: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    // 수정·톰스톤 삭제 때 CommentService가 갱신한다 - val이던 동안은 생성 시각에 고정돼 있었다
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    val member: TableMember? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", insertable = false, updatable = false)
    val post: TablePost? = null,
)
