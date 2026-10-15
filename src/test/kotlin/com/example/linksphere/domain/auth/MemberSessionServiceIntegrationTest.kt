package com.example.linksphere.domain.auth

import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.global.exception.InvalidTokenException
import com.example.linksphere.global.exception.SessionMismatchException
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

// refresh 회원 불일치(assertSameMember)로 거절하면 rotate 트랜잭션이 롤백돼 앞의 소비(consumeRefreshIfActive)도 되돌아가는지,
// 재사용 탐지의 계열 폐기(revokeFamily, REQUIRES_NEW)는 그 롤백에도 남는지 실제 Postgres에서 확인한다(FE docs/AUTH.md §8-B
// "쿠키 주인이 다른 회원이라 갱신이 거절될 때"). MemberSessionServiceTest는 mock이라 롤백을 보지 못한다.
//
// 테스트 트랜잭션 밖에서 돈다 - @DataJpaTest 기본값대로 테스트를 트랜잭션 하나로 감싸면 rotate가 거기에 참여해 롤백이 테스트
// 끝까지 미뤄지고, 이어지는 revokeFamily(REQUIRES_NEW)는 그 트랜잭션이 쥔 행 잠금을 기다리다 멈춘다. 쓰기가 실제로 커밋되므로
// 끝나면 회원을 지운다.
@PostgresIntegrationTest
@Import(MemberSessionService::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MemberSessionServiceIntegrationTest {

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var memberSessionService: MemberSessionService

    private val memberA = UUID.randomUUID()
    private val memberB = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        jdbc.update("INSERT INTO members (id) VALUES (?), (?)", memberA, memberB)
    }

    @AfterEach
    fun cleanUp() {
        // members를 지우면 member_sessions가 FK cascade로 함께 지워진다
        jdbc.update("DELETE FROM members WHERE id IN (?, ?)", memberA, memberB)
    }

    // 다른 탭에서 B로 로그인해 쿠키가 B로 바뀐 뒤, A 토큰으로 나갔던 요청이 TOKEN_EXPIRED를 받고 그 토큰을 실어 refresh한다
    @Test
    fun `다른 회원 힌트로 거절되면 소비가 롤백돼 같은 쿠키로 다시 갱신할 수 있다`() {
        val cookie = memberSessionService.createSession(memberB)
        val hint = memberSessionService.createSession(memberA)
        jdbc.update("UPDATE member_sessions SET access_expires_at = ? WHERE access_token_hash = ?", minuteAgo(), SecureToken.hash(hint.accessToken))

        assertThrows(SessionMismatchException::class.java) {
            memberSessionService.rotate(cookie.refreshToken, hint.accessToken)
        }

        assertNull(revokedAt(cookie.refreshToken))
        assertEquals(1L, sessionCount(memberB))

        // 소비가 남아 있었다면 이미 회전된 쿠키로 보고 재사용 탐지(계열 폐기)로 끝난다
        val reissued = memberSessionService.rotate(cookie.refreshToken)

        assertEquals(AccessTokenCheck.Valid(memberB, familyOf(cookie.refreshToken)), memberSessionService.checkAccessToken(reissued.accessToken))
        assertEquals(2L, sessionCount(memberB))
    }

    // 같은 계정의 재로그인·비밀번호 변경은 계열이 바뀐다 - 회원이 같으면 통과해야 한다
    @Test
    fun `같은 회원의 다른 계열 힌트면 쿠키의 계열로 발급한다`() {
        val cookie = memberSessionService.createSession(memberA)
        val hint = memberSessionService.createSession(memberA)

        val issued = memberSessionService.rotate(cookie.refreshToken, hint.accessToken)

        assertEquals(AccessTokenCheck.Valid(memberA, familyOf(cookie.refreshToken)), memberSessionService.checkAccessToken(issued.accessToken))
        assertNotNull(revokedAt(cookie.refreshToken))
    }

    // 회원 확인은 재사용 판정 뒤에 하므로 이미 회전된 refresh에 다른 회원 토큰을 붙여도 탐지를 피하지 못한다. 계열 폐기는
    // REQUIRES_NEW로 따로 커밋돼 rotate가 InvalidTokenException으로 롤백돼도 남아야 한다
    @Test
    fun `유예 밖 재사용이면 다른 회원 힌트여도 계열 폐기가 커밋되고 InvalidTokenException`() {
        val stolen = memberSessionService.createSession(memberB)
        val rotated = memberSessionService.rotate(stolen.refreshToken)
        // REUSE_LEEWAY(10초) 밖에 회전된 것으로 만든다
        jdbc.update("UPDATE member_sessions SET revoked_at = ? WHERE refresh_token_hash = ?", minuteAgo(), SecureToken.hash(stolen.refreshToken))
        val hint = memberSessionService.createSession(memberA)

        assertThrows(InvalidTokenException::class.java) {
            memberSessionService.rotate(stolen.refreshToken, hint.accessToken)
        }

        assertNotNull(revokedAt(rotated.refreshToken))
        assertEquals(2L, sessionCount(memberB))
        assertNull(revokedAt(hint.refreshToken))
    }

    // rotate는 JVM 시각(Instant.now())과 비교하므로 DB now()가 아니라 JVM 시각으로 넣는다 - Docker VM 시계와 어긋날 수 있다
    private fun minuteAgo(): Timestamp = Timestamp.from(Instant.now().minusSeconds(60))

    private fun revokedAt(rawRefresh: String): Timestamp? = jdbc.queryForObject(
        "SELECT revoked_at FROM member_sessions WHERE refresh_token_hash = ?",
        Timestamp::class.java,
        SecureToken.hash(rawRefresh),
    )

    private fun familyOf(rawRefresh: String): UUID = jdbc.queryForObject(
        "SELECT family_id FROM member_sessions WHERE refresh_token_hash = ?",
        UUID::class.java,
        SecureToken.hash(rawRefresh),
    )!!

    private fun sessionCount(memberId: UUID): Long = jdbc.queryForObject(
        "SELECT COUNT(*) FROM member_sessions WHERE member_id = ?",
        Long::class.java,
        memberId,
    )!!
}
