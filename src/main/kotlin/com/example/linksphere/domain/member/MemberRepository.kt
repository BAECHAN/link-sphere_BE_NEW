package com.example.linksphere.domain.member

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface MemberRepository : JpaRepository<TableMember, UUID> {
    fun findByEmail(email: String): TableMember?
    fun existsByEmail(email: String): Boolean
    fun existsByNicknameIgnoreCase(nickname: String): Boolean

    // 고아 이미지 정리 도구(OrphanImageCleanupRunner)용
    @Query("SELECT m.image FROM TableMember m WHERE m.image IS NOT NULL")
    fun findAllImageUrls(): List<String>
}
