package com.example.linksphere.domain.member

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "members")
class TableMember(
        @Id
        @GeneratedValue(strategy = GenerationType.AUTO)
        @Column(name = "id", nullable = false)
        val id: UUID? = null,
        @Column(name = "email", nullable = false, unique = true) val email: String,
        @Column(name = "password", nullable = false) // Encrypted password
        val password: String,
        @Column(name = "nickname") val nickname: String? = null,
        @Column(name = "image") val image: String? = null,
        @Column(name = "created_at") val createdAt: LocalDateTime? = LocalDateTime.now(),
        @Column(name = "updated_at") val updatedAt: LocalDateTime? = LocalDateTime.now()
)
