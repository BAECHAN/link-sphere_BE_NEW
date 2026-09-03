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

    // RSS 피드 자동 수집 봇 계정 조회. 파생 쿼리(findFirstByIsBotTrue)는 "Is" 접두어가
    // 프로퍼티명(isBot)과 겹쳐 파싱이 모호해질 수 있어 명시적 @Query를 쓴다.
    @Query("SELECT m FROM TableMember m WHERE m.isBot = true")
    fun findFirstByIsBotTrue(): TableMember?
}
