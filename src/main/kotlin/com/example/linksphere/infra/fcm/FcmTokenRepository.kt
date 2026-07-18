package com.example.linksphere.infra.fcm

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface FcmTokenRepository : JpaRepository<TableFcmToken, UUID> {
    fun findAllByUserId(userId: UUID): List<TableFcmToken>
    fun deleteByToken(token: String)
    fun deleteByUserId(userId: UUID)
    fun existsByToken(token: String): Boolean
}
