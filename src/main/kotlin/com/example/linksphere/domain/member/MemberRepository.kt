package com.example.linksphere.domain.member

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MemberRepository : JpaRepository<TableMember, UUID> {
    fun findByEmail(email: String): TableMember?
    fun existsByEmail(email: String): Boolean
    fun existsByNickname(nickname: String): Boolean
}
