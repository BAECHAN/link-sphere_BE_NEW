package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.global.common.IdempotentCreate
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.infra.mail.MailService
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 메일 요청의 중복 방지를 실제 Postgres로 본다(docs/plans/2026-11-04-idempotent-create.md). 할당 id라 save가 merge고 INSERT는
// 커밋 때 나가므로, 겹친 요청의 위반은 @Transactional 경계 밖에서야 잡힌다. 그 쪽의 afterCommit 메일은 나가지 않아야 한다.
// 커밋이 겹쳐야 하므로 테스트 트랜잭션 밖에서 돌고 끝나면 지운다.
@PostgresIntegrationTest
@Import(PasswordResetService::class, AuthService::class, MemberService::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MailRequestIdempotencyIntegrationTest {

    @Autowired private lateinit var passwordResetService: PasswordResetService

    @Autowired private lateinit var authService: AuthService

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var memberSessionService: MemberSessionService

    @MockitoBean private lateinit var passwordEncoder: PasswordEncoder

    @MockitoBean private lateinit var mailService: MailService

    @MockitoBean private lateinit var rateLimitService: RateLimitService

    private val memberId = UUID.randomUUID()
    private val email = "$memberId@example.com"
    private val executor = Executors.newFixedThreadPool(1)

    @BeforeEach
    fun seed() {
        jdbc.update(
            "INSERT INTO members (id, email, nickname, password, email_verified) VALUES (?, ?, ?, 'enc', false)",
            memberId,
            email,
            memberId.toString(),
        )
    }

    @AfterEach
    fun cleanUp() {
        executor.shutdownNow()
        // members를 지우면 member_action_tokens가 FK cascade로 함께 지워진다
        jdbc.update("DELETE FROM members WHERE id = ?", memberId)
    }

    @Test
    fun `겹친 재설정 요청은 토큰 1행만 남고 진 쪽은 예외도 메일도 없다`() {
        val key = UUID.randomUUID()

        overlapWithCommittedToken(key, MemberActionTokenPurpose.PASSWORD_RESET) {
            IdempotentCreate.ignoringLoss(TableMemberActionToken.IDEMPOTENCY_INDEX) {
                passwordResetService.requestReset(email, null, key)
            }
        }

        assertEquals(1, countTokens())
        verify(mailService, never()).send(anyString(), anyString(), anyString())
    }

    @Test
    fun `겹친 인증 메일 요청은 토큰 1행만 남고 진 쪽은 예외도 메일도 없다`() {
        val key = UUID.randomUUID()

        overlapWithCommittedToken(key, MemberActionTokenPurpose.EMAIL_VERIFY) {
            IdempotentCreate.ignoringLoss(TableMemberActionToken.IDEMPOTENCY_INDEX) {
                authService.requestEmailVerification(email, null, key)
            }
        }

        assertEquals(1, countTokens())
        verify(mailService, never()).send(anyString(), anyString(), anyString())
    }

    @Test
    fun `같은 키의 토큰이 유효하면 다시 보내지 않고, 만료되면 키 없는 새 토큰으로 한 통 더 보낸다`() {
        val key = UUID.randomUUID()

        passwordResetService.requestReset(email, null, key)
        passwordResetService.requestReset(email, null, key)

        assertEquals(1, countTokens())
        verify(mailService, times(1)).send(anyString(), anyString(), anyString())

        jdbc.update("UPDATE member_action_tokens SET expires_at = now() - interval '1 minute' WHERE member_id = ?", memberId)
        passwordResetService.requestReset(email, null, key)

        assertEquals(2, countTokens())
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM member_action_tokens WHERE member_id = ? AND idempotency_key IS NOT NULL", Int::class.java, memberId))
        verify(mailService, times(2)).send(anyString(), anyString(), anyString())
    }

    @Test
    fun `같은 키의 토큰을 이미 썼으면 키 없는 새 토큰으로 한 통 더 보낸다`() {
        val key = UUID.randomUUID()

        authService.requestEmailVerification(email, null, key)
        jdbc.update("UPDATE member_action_tokens SET consumed_at = now() WHERE member_id = ?", memberId)
        authService.requestEmailVerification(email, null, key)

        assertEquals(2, countTokens())
        verify(mailService, times(2)).send(anyString(), anyString(), anyString())
    }

    /** 다른 트랜잭션이 같은 키의 토큰을 먼저 INSERT하고 커밋을 늦추는 동안 [loser]를 돌린다. */
    private fun overlapWithCommittedToken(key: UUID, purpose: String, loser: () -> Unit) {
        val inserted = CountDownLatch(1)
        val winner = executor.submit {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbc.update(
                    "INSERT INTO member_action_tokens (id, member_id, purpose, token_hash, expires_at, idempotency_key) " +
                        "VALUES (?, ?, ?, ?, now() + interval '1 hour', ?)",
                    UUID.randomUUID(),
                    memberId,
                    purpose,
                    "h".repeat(64),
                    key,
                )
                inserted.countDown()
                Thread.sleep(500)
            }
        }
        inserted.await(10, TimeUnit.SECONDS)

        loser()
        winner.get(15, TimeUnit.SECONDS)
    }

    private fun countTokens(): Int = jdbc.queryForObject("SELECT COUNT(*) FROM member_action_tokens WHERE member_id = ?", Int::class.java, memberId)!!
}
