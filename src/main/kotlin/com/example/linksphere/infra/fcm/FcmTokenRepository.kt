package com.example.linksphere.infra.fcm

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface FcmTokenRepository : JpaRepository<TableFcmToken, UUID> {
    fun findAllByUserId(userId: UUID): List<TableFcmToken>
    fun deleteByToken(token: String)
    fun deleteByUserId(userId: UUID)
    fun existsByToken(token: String): Boolean
}
