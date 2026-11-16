package com.example.linksphere.infra.fcm

import com.example.linksphere.domain.auth.MemberSessionService
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.support.PostgresIntegrationTest
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 알림을 보내기 전에 세션이 죽은 기기의 토큰을 지우는 일을 실제 Postgres에서 본다(docs/plans/2026-11-16-be-structure-stage2.md PR-7b).
 *
 * 예전에는 fcm의 DELETE 한 문장이 member_sessions를 서브쿼리로 읽었다. 지금은 살아 있는 세션 계열을 auth에 묻고
 * (MemberSessionService.findLiveFamilyIds) 죽은 계열의 토큰만 지운다 - 지워지는 토큰이 예전과 같은지 확인한다.
 *
 * FcmService.sendToUser가 별도 트랜잭션(REQUIRES_NEW)이라 테스트 트랜잭션 밖에서 넣고, 끝나면 넣은 회원을 지운다
 * (FcmServiceIntegrationTest와 같은 이유). 실제 발송은 하지 않는다 - 기본 FirebaseApp이 없어 발송 직전에 예외가 나고
 * FcmService가 삼킨다. 토큰 정리는 그 전에 끝나 함께 커밋된다.
 */
@PostgresIntegrationTest
@Import(FcmService::class, MemberSessionService::class, MemberService::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FcmStaleTokenIntegrationTest {

    @Autowired private lateinit var fcmService: FcmService

    @Autowired private lateinit var jdbc: JdbcTemplate

    private val memberId = UUID.randomUUID()
    private val otherMemberId = UUID.randomUUID()

    private lateinit var firebaseApp: FirebaseApp

    @BeforeEach
    fun setUp() {
        // FcmService는 FirebaseApp이 하나도 없으면 토큰 정리 전에 끝낸다 - 오프라인 가짜 자격증명으로 이름 있는 앱만 만든다
        firebaseApp =
            FirebaseApp.initializeApp(
                FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.create(AccessToken("test-token", null)))
                    .setProjectId("link-sphere-test")
                    .build(),
                "fcm-stale-token-integration-test",
            )
        jdbc.update("INSERT INTO members (id) VALUES (?)", memberId)
        jdbc.update("INSERT INTO members (id) VALUES (?)", otherMemberId)
    }

    @AfterEach
    fun tearDown() {
        firebaseApp.delete()
        jdbc.update("DELETE FROM fcm_tokens WHERE user_id IN (?, ?)", memberId, otherMemberId)
        jdbc.update("DELETE FROM member_sessions WHERE member_id IN (?, ?)", memberId, otherMemberId)
        jdbc.update("DELETE FROM members WHERE id IN (?, ?)", memberId, otherMemberId)
    }

    @Test
    fun `살아 있는 세션 계열의 토큰만 남기고 계열이 없거나 죽은 토큰을 지운다`() {
        val liveFamily = insertSession(memberId)
        // 회전으로 소비된 행이 있어도 같은 계열에 살아 있는 행이 하나라도 있으면 그 계열은 살아 있다
        insertSession(memberId, familyId = liveFamily, revoked = true)
        val revokedFamily = insertSession(memberId, revoked = true)
        val expiredFamily = insertSession(memberId, expired = true)
        // 다른 회원의 살아 있는 계열 - 이 회원의 토큰에 적혀 있어도 이 회원의 세션이 아니다
        val othersFamily = insertSession(otherMemberId)

        insertToken(memberId, "live", liveFamily)
        insertToken(memberId, "legacy-without-family", null)
        insertToken(memberId, "revoked", revokedFamily)
        insertToken(memberId, "expired", expiredFamily)
        insertToken(memberId, "others-family", othersFamily)
        insertToken(memberId, "unknown-family", UUID.randomUUID())
        insertToken(otherMemberId, "other-member-live", othersFamily)
        insertToken(otherMemberId, "other-member-legacy", null)

        fcmService.sendToUser(memberId, "새 댓글", "내용")

        assertEquals(listOf("live"), tokensOf(memberId))
        // 다른 회원의 토큰은 건드리지 않는다
        assertEquals(listOf("other-member-legacy", "other-member-live"), tokensOf(otherMemberId))
    }

    @Test
    fun `살아 있는 세션이 하나도 없으면 그 회원의 토큰을 모두 지운다`() {
        val revokedFamily = insertSession(memberId, revoked = true)
        insertToken(memberId, "revoked", revokedFamily)
        insertToken(memberId, "legacy-without-family", null)

        fcmService.sendToUser(memberId, "새 댓글", "내용")

        assertEquals(emptyList<String>(), tokensOf(memberId))
    }

    private fun tokensOf(userId: UUID): List<String> = jdbc.queryForList("SELECT token FROM fcm_tokens WHERE user_id = ? ORDER BY token", String::class.java, userId)

    private fun insertSession(member: UUID, familyId: UUID = UUID.randomUUID(), revoked: Boolean = false, expired: Boolean = false): UUID {
        jdbc.update(
            "INSERT INTO member_sessions (id, member_id, access_token_hash, refresh_token_hash, access_expires_at, refresh_expires_at, revoked_at, family_id) " +
                "VALUES (?, ?, ?, ?, now() + interval '1 hour', now() + (CASE WHEN ? THEN interval '-1 hour' ELSE interval '1 day' END), " +
                "CASE WHEN ? THEN now() END, ?)",
            UUID.randomUUID(),
            member,
            SecureToken.hash(UUID.randomUUID().toString()),
            SecureToken.hash(UUID.randomUUID().toString()),
            expired,
            revoked,
            familyId,
        )
        return familyId
    }

    private fun insertToken(userId: UUID, token: String, familyId: UUID?) {
        jdbc.update("INSERT INTO fcm_tokens (id, user_id, token, session_family_id) VALUES (?, ?, ?, ?)", UUID.randomUUID(), userId, token, familyId)
    }
}
