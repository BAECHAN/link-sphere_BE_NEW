package com.example.linksphere.domain.member

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface MemberRepository : JpaRepository<TableMember, UUID> {
    fun findByEmail(email: String): TableMember?
    fun existsByEmail(email: String): Boolean
}
