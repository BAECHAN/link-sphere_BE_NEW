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
        @Column(name = "created_at", nullable = false)
        val createdAt: LocalDateTime = LocalDateTime.now(),
        @Column(name = "updated_at", nullable = false)
        val updatedAt: LocalDateTime = LocalDateTime.now(),
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "user_id", insertable = false, updatable = false)
        val member: TableMember? = null,
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "post_id", insertable = false, updatable = false)
        val post: TablePost? = null
)
