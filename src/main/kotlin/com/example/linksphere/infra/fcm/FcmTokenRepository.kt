package com.example.linksphere.infra.fcm

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface FcmTokenRepository : JpaRepository<TableFcmToken, UUID> {
    fun findAllByUserId(userId: UUID): List<TableFcmToken>

    // 삭제는 모두 일괄 DELETE — derived delete(엔티티를 읽어 지움)는 같은 행을 동시에 지우는 요청
    // (여러 탭 로그아웃, 겹친 알림 작업의 만료 토큰 정리)과 부딪히면 StaleObjectStateException을 낸다.
    // 읽은 뒤 지우는 사이 upsertToken이 행을 다른 유저에게 넘기면 그 유저의 토큰을 지우는 문제도 없다.
    @Modifying
    @Query("DELETE FROM TableFcmToken t WHERE t.token = :token")
    fun deleteByToken(@Param("token") token: String): Int

    @Modifying
    @Query("DELETE FROM TableFcmToken t WHERE t.userId = :userId AND t.token = :token")
    fun deleteByUserIdAndToken(@Param("userId") userId: UUID, @Param("token") token: String): Int

    @Modifying
    @Query("DELETE FROM TableFcmToken t WHERE t.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: UUID): Int

    // 같은 토큰이 이미 있으면(다른 유저가 쓰던 기기 재사용 포함) 소유자·회전계열을 덮어쓴다.
    // findByToken 후 분기 저장하던 기존 방식은, 발송 쪽의 죽은 토큰 정리(FcmService.removeStaleTokens)와
    // 타이밍이 겹치면 Hibernate가 StaleObjectStateException을 낼 수 있어 단일 SQL 문으로
    // 대체한다. id는 신규 삽입 시에만 쓰인다(기존 행이면 ON CONFLICT가 UPDATE로 대체).
    @Modifying
    @Query(
        value = """
            INSERT INTO fcm_tokens (id, user_id, token, platform, session_family_id, created_at, updated_at)
            VALUES (:id, :userId, :token, :platform, :sessionFamilyId, now(), now())
            ON CONFLICT (token) DO UPDATE
                SET user_id = :userId, platform = :platform, session_family_id = :sessionFamilyId, updated_at = now()
        """,
        nativeQuery = true,
    )
    fun upsertToken(
        @Param("id") id: UUID,
        @Param("userId") userId: UUID,
        @Param("token") token: String,
        @Param("platform") platform: String,
        @Param("sessionFamilyId") sessionFamilyId: UUID?,
    )

    // 아래 둘은 댓글 발송 시점(FcmService.sendToUser)에, 이 유저의 토큰 중 회전 계열이 이미 죽은(또는 애초에 없는) 것을
    // 지운다 - 로그인 세션이 자연 만료된 기기로는 더 이상 푸시가 가지 않게 한다. 어느 계열이 죽었는지는 auth에 물어
    // 받는다(MemberSessionService.findLiveFamilyIds) - 예전에는 이 저장소가 member_sessions를 서브쿼리로 직접 읽었다.
    // session_family_id는 회전한 세션 행들이 함께 쓰는 계열 id라 FK로 걸 대상이 없다(fcm_tokens.user_id 쪽은 members에
    // ON DELETE CASCADE FK가 있다, 운영 스키마 덤프 src/test/resources/db/schema.sql의 fcm_tokens_user_id_fkey).
    @Modifying
    @Query("DELETE FROM TableFcmToken t WHERE t.userId = :userId AND t.sessionFamilyId IS NULL")
    fun deleteByUserIdAndSessionFamilyIdIsNull(@Param("userId") userId: UUID): Int

    // "살아 있는 계열이 아닌 것"이 아니라 "죽은 것으로 확인한 계열"을 지운다 - 살아 있는 계열을 읽은 뒤 새로 로그인한
    // 기기가 등록한 토큰(읽은 목록에 없는 새 계열)을 함께 지우지 않게 한다. 빈 목록으로 부르지 않는다.
    @Modifying
    @Query("DELETE FROM TableFcmToken t WHERE t.userId = :userId AND t.sessionFamilyId IN :familyIds")
    fun deleteByUserIdAndSessionFamilyIdIn(@Param("userId") userId: UUID, @Param("familyIds") familyIds: Collection<UUID>): Int
}
