package com.example.linksphere.infra.fcm

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface FcmTokenRepository : JpaRepository<TableFcmToken, UUID> {
    fun findAllByUserId(userId: UUID): List<TableFcmToken>
    fun findByToken(token: String): TableFcmToken?
    fun deleteByToken(token: String)
    fun deleteByUserIdAndToken(userId: UUID, token: String)
    fun deleteByUserId(userId: UUID)
}
