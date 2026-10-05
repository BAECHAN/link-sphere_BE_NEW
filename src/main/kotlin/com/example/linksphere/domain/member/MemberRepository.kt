package com.example.linksphere.domain.member

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface MemberRepository : JpaRepository<TableMember, UUID> {
    fun findByEmail(email: String): TableMember?
    fun existsByEmail(email: String): Boolean
    fun existsByNicknameIgnoreCase(nickname: String): Boolean

    // 고아 이미지 정리 도구(OrphanImageCleanupRunner)용
    @Query("SELECT m.image FROM TableMember m WHERE m.image IS NOT NULL")
    fun findAllImageUrls(): List<String>

    // 댓글 이미지를 지우기 직전에 이 URL을 누군가 아바타로 쓰는지 확인한다(CommentService 삭제 경로)
    fun existsByImage(image: String): Boolean

    // RSS 피드 자동 수집 봇 계정 조회. 파생 쿼리(findFirstByIsBotTrue)는 "Is" 접두어가
    // 프로퍼티명(isBot)과 겹쳐 파싱이 모호해질 수 있어 명시적 @Query를 쓴다.
    @Query("SELECT m FROM TableMember m WHERE m.isBot = true")
    fun findFirstByIsBotTrue(): TableMember?

    // AccountPurgeService가 매일 퍼지 대상을 고를 때 쓴다. WHERE를 claimForPurge와 정확히
    // 같은 조건(cutoff 이전, 아직 퍼지 전)으로 맞춰야 여기서 뽑은 id가 claim에서 다시
    // 걸러지지 않는다.
    @Query("SELECT m.id FROM TableMember m WHERE m.deletionRequestedAt < :cutoff AND m.deletedAt IS NULL ORDER BY m.deletionRequestedAt")
    fun findIdsPendingPurge(@Param("cutoff") cutoff: Instant?, pageable: Pageable): List<UUID>

    // 로그인 성공 시 탈퇴 신청을 취소한다(AuthService.login). WHERE 조건이 있는 조건부
    // UPDATE 자체가 퍼지(claimForPurge)와의 경합을 가르는 DB 레벨 락 역할을 한다 -
    // MemberSessionRepository.consumeRefreshIfActive와 같은 패턴. 영향받은 행 수가 0이면
    // 이미 퍼지가 먼저 그 회원을 가져간 것이다(호출부가 로그인 실패로 처리한다).
    @Modifying
    @Query("UPDATE TableMember m SET m.deletionRequestedAt = NULL WHERE m.id = :id AND m.deletionRequestedAt IS NOT NULL AND m.deletedAt IS NULL")
    fun cancelDeletionRequest(@Param("id") id: UUID?): Int

    // AccountPurgeService가 퍼지를 시작하기 전에 "내가 이 회원을 처리할 권리를 얻었는지"
    // 확정하는 데 쓴다. cutoff 재검증(WHERE ... < :cutoff)이 findIdsPendingPurge로 목록을
    // 뽑은 뒤 그 사이 로그인으로 복구된 회원을 걸러낸다. deletedAt을 이 UPDATE에서 같이
    // 세팅해 "claim 성공 = 퍼지 완료로 확정"을 한 문장으로 만든다.
    @Modifying
    @Query("UPDATE TableMember m SET m.deletedAt = :now WHERE m.id = :id AND m.deletionRequestedAt < :cutoff AND m.deletedAt IS NULL")
    fun claimForPurge(@Param("id") id: UUID?, @Param("cutoff") cutoff: Instant?, @Param("now") now: Instant?): Int
}
