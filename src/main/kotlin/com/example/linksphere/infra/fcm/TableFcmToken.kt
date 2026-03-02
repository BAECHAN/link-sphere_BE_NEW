package com.example.linksphere.infra.fcm

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "fcm_tokens")
class TableFcmToken(
    @Id
    @Column(name = "id", nullable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "token", nullable = false, unique = true, columnDefinition = "text")
    val token: String,

    // WEB, ANDROID, IOS
    @Column(name = "platform", nullable = false, length = 10)
    val platform: String = "WEB",

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
