package com.example.linksphere.infra.fcm

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface FcmTokenRepository : JpaRepository<TableFcmToken, UUID> {
    fun findAllByUserId(userId: UUID): List<TableFcmToken>
    fun findByToken(token: String): TableFcmToken?
    fun deleteByToken(token: String)
    fun deleteByUserIdAndToken(userId: UUID, token: String)
    fun deleteByUserId(userId: UUID)

    // 같은 토큰이 이미 있으면(다른 유저가 쓰던 기기 재사용 포함) 소유자·회전계열을 덮어쓴다.
    // findByToken 후 분기 저장하던 기존 방식은, 발송 쪽의 deleteStaleTokensForUser와
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

    // 댓글 발송 시점(FcmService.sendToUser)에, 이 유저의 토큰 중 회전 계열이 이미 죽은
    // (또는 애초에 없는) 것을 지운다 - 로그인 세션이 자연 만료된 기기로는 더 이상 푸시가
    // 가지 않게 한다. member_sessions와 FK/연관관계로 묶지 않고 EXISTS 서브쿼리로만
    // 참조한다(fcm_tokens.user_id도 원래 FK가 아닌 이 레포의 기존 관례).
    @Modifying
    @Query(
        value = """
            DELETE FROM fcm_tokens t
            WHERE t.user_id = :userId
              AND (
                t.session_family_id IS NULL
                OR NOT EXISTS (
                    SELECT 1 FROM member_sessions s
                    WHERE s.family_id = t.session_family_id
                      AND s.member_id = :userId
                      AND s.revoked_at IS NULL
                      AND s.refresh_expires_at > now()
                )
              )
        """,
        nativeQuery = true,
    )
    fun deleteStaleTokensForUser(@Param("userId") userId: UUID): Int
}
