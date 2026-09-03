package com.example.linksphere.domain.member

import jakarta.persistence.*
import org.hibernate.annotations.DynamicUpdate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "members")
@DynamicUpdate
class TableMember(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false)
    val id: UUID? = null,
    @Column(name = "email", nullable = false, unique = true) val email: String,
    @Column(name = "password", nullable = false) // Encrypted password
    val password: String,
    @Column(name = "nickname") var nickname: String? = null,
    @Column(name = "image") var image: String? = null,
    @Column(name = "created_at") val createdAt: LocalDateTime? = LocalDateTime.now(),
    @Column(name = "updated_at") var updatedAt: LocalDateTime? = LocalDateTime.now(),
    // RSS 피드 자동 수집 봇 계정 여부. sql/create_feed_sources.sql이 컬럼 추가 + 봇 계정 INSERT를
    // 함께 실행하므로, 이 필드가 매핑된 코드가 배포되기 전에 반드시 그 SQL부터 실행해야 한다
    // (컬럼 없이 배포하면 모든 member SELECT가 실패한다).
    @Column(name = "is_bot", nullable = false) val isBot: Boolean = false,
)
