package com.example.linksphere.domain.auth

import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.global.exception.InvalidTokenException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant
import java.util.UUID

/**
 * ArgumentMatchers.any()/eq()는 Kotlin non-null 파라미터(Instant, TableMemberSession 등)에
 * 쓰면 null을 반환해 즉시 NPE가 나고, 그 예외가 Mockito의 매처 스택을 미소비 상태로 남겨
 * 같은 JVM에서 도는 무관한 다른 테스트 클래스까지 연쇄로 깨뜨린다(실측 확인). 이 파일은
 * 그 어떤 matcher도 쓰지 않고 ArgumentCaptor(캡처만, 비교 안 함)와 verify + concrete 값,
 * verifyNoMoreInteractions만으로 "아무 일도 안 함"까지 검증한다.
 */
@ExtendWith(MockitoExtension::class)
class MemberSessionServiceTest {

    @Mock private lateinit var memberSessionRepository: MemberSessionRepository

    @InjectMocks private lateinit var memberSessionService: MemberSessionService

    private fun session(
        memberId: UUID = UUID.randomUUID(),
        accessTokenHash: String = "any-access-hash",
        refreshTokenHash: String = "any-refresh-hash",
        refreshExpiresAt: Instant = Instant.now().plusSeconds(3600),
        revokedAt: Instant? = null,
        familyId: UUID = UUID.randomUUID(),
    ) = TableMemberSession(
        memberId = memberId,
        accessTokenHash = accessTokenHash,
        refreshTokenHash = refreshTokenHash,
        accessExpiresAt = Instant.now().plusSeconds(3600),
        refreshExpiresAt = refreshExpiresAt,
        revokedAt = revokedAt,
        familyId = familyId,
    )

    // ── createSession ────────────────────────────────────────────

    @Test
    fun `createSession은 새 행을 저장하고 raw 토큰 쌍을 반환한다`() {
        val memberId = UUID.randomUUID()

        val issued = memberSessionService.createSession(memberId)

        val captor = ArgumentCaptor.forClass(TableMemberSession::class.java)
        verify(memberSessionRepository).save(captor.capture())
        val saved = captor.value

        assertEquals(memberId, saved.memberId)
        assertEquals(604800L, issued.refreshExpiresInSeconds)
        // 원문 토큰과 저장된 해시가 다르다 - 원문 자체는 저장하지 않는다.
        assertNotEquals(issued.accessToken, saved.accessTokenHash)
        assertNotEquals(issued.refreshToken, saved.refreshTokenHash)
    }

    // ── rotate ───────────────────────────────────────────────────

    @Test
    fun `rotate는 존재하지 않는 refresh 토큰이면 InvalidTokenException`() {
        val hash = SecureToken.hash("unknown-token")
        `when`(memberSessionRepository.findByRefreshTokenHash(hash)).thenReturn(null)

        assertThrows(InvalidTokenException::class.java) {
            memberSessionService.rotate("unknown-token")
        }

        verify(memberSessionRepository).findByRefreshTokenHash(hash)
        verifyNoMoreInteractions(memberSessionRepository)
    }

    @Test
    fun `rotate는 유효한 refresh면 기존 행을 revoke하고 같은 family_id로 새 행을 만든다`() {
        val familyId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val refreshExpiresAt = Instant.now().plusSeconds(1000)
        val rawToken = "valid-refresh-token"
        val current = session(
            memberId = memberId,
            refreshTokenHash = SecureToken.hash(rawToken),
            refreshExpiresAt = refreshExpiresAt,
            familyId = familyId,
        )
        `when`(memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawToken))).thenReturn(current)

        val issued = memberSessionService.rotate(rawToken)

        // 1회차 save: 기존 행을 revoke 처리, 2회차 save: 새 행
        val captor = ArgumentCaptor.forClass(TableMemberSession::class.java)
        verify(memberSessionRepository, times(2)).save(captor.capture())

        val revokedOld = captor.allValues[0]
        assertEquals(current, revokedOld)
        assertNotEquals(null, revokedOld.revokedAt)

        val newSession = captor.allValues[1]
        assertEquals(memberId, newSession.memberId)
        assertEquals(familyId, newSession.familyId)
        // 절대 수명은 연장되지 않는다 - 원래 refreshExpiresAt을 그대로 물려받는다.
        assertEquals(refreshExpiresAt, newSession.refreshExpiresAt)

        assertNotEquals(0L, issued.refreshExpiresInSeconds)
    }

    @Test
    fun `rotate는 이미 revoke된 refresh가 다시 제시되면(재사용) 같은 계열 전체를 폐기하고 예외를 던진다`() {
        val familyId = UUID.randomUUID()
        val rawToken = "reused-token"
        val alreadyRevoked = session(
            refreshTokenHash = SecureToken.hash(rawToken),
            familyId = familyId,
            revokedAt = Instant.now().minusSeconds(60),
        )
        `when`(memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawToken))).thenReturn(alreadyRevoked)

        assertThrows(InvalidTokenException::class.java) {
            memberSessionService.rotate(rawToken)
        }

        val familyCaptor = ArgumentCaptor.forClass(UUID::class.java)
        val nowCaptor = ArgumentCaptor.forClass(Instant::class.java)
        verify(memberSessionRepository).revokeFamily(familyCaptor.capture(), nowCaptor.capture())
        assertEquals(familyId, familyCaptor.value)

        verify(memberSessionRepository).findByRefreshTokenHash(SecureToken.hash(rawToken))
        verifyNoMoreInteractions(memberSessionRepository)
    }

    @Test
    fun `rotate는 만료된 refresh면(revoke는 안 됐지만) 예외를 던지고 계열을 폐기하지 않는다`() {
        val rawToken = "expired-token"
        val expired = session(
            refreshTokenHash = SecureToken.hash(rawToken),
            refreshExpiresAt = Instant.now().minusSeconds(1),
        )
        `when`(memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawToken))).thenReturn(expired)

        assertThrows(InvalidTokenException::class.java) {
            memberSessionService.rotate(rawToken)
        }

        // revokeFamily도 save도 호출되지 않는다 - 찾기 조회 하나만 있어야 한다.
        verify(memberSessionRepository).findByRefreshTokenHash(SecureToken.hash(rawToken))
        verifyNoMoreInteractions(memberSessionRepository)
    }

    // ── revokeByRefreshToken (로그아웃, 이 기기만) ───────────────────

    @Test
    fun `revokeByRefreshToken은 토큰이 없으면 아무 일도 하지 않는다`() {
        memberSessionService.revokeByRefreshToken(null)
        memberSessionService.revokeByRefreshToken("")

        verifyNoInteractions(memberSessionRepository)
    }

    @Test
    fun `revokeByRefreshToken은 못 찾으면 아무 일도 하지 않는다`() {
        val hash = SecureToken.hash("unknown")
        `when`(memberSessionRepository.findByRefreshTokenHash(hash)).thenReturn(null)

        memberSessionService.revokeByRefreshToken("unknown")

        verify(memberSessionRepository).findByRefreshTokenHash(hash)
        verifyNoMoreInteractions(memberSessionRepository)
    }

    @Test
    fun `revokeByRefreshToken은 찾으면 revokedAt을 채워 저장한다`() {
        val rawToken = "some-token"
        val target = session(refreshTokenHash = SecureToken.hash(rawToken))
        `when`(memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawToken))).thenReturn(target)

        memberSessionService.revokeByRefreshToken(rawToken)

        assertNotEquals(null, target.revokedAt)
        verify(memberSessionRepository).save(target)
    }

    @Test
    fun `revokeByRefreshToken은 이미 revoke된 행이면 다시 저장하지 않는다`() {
        val rawToken = "some-token"
        val alreadyRevoked = session(refreshTokenHash = SecureToken.hash(rawToken), revokedAt = Instant.now())
        `when`(memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawToken))).thenReturn(alreadyRevoked)

        memberSessionService.revokeByRefreshToken(rawToken)

        verify(memberSessionRepository).findByRefreshTokenHash(SecureToken.hash(rawToken))
        verifyNoMoreInteractions(memberSessionRepository)
    }

    // ── revokeAllForMember (로그아웃 전체·비밀번호변경·탈퇴에서 사용) ──

    @Test
    fun `revokeAllForMember는 해당 회원의 활성 세션을 한 쿼리로 폐기한다`() {
        val memberId = UUID.randomUUID()

        memberSessionService.revokeAllForMember(memberId)

        val memberCaptor = ArgumentCaptor.forClass(UUID::class.java)
        val nowCaptor = ArgumentCaptor.forClass(Instant::class.java)
        verify(memberSessionRepository).revokeAllActiveForMember(memberCaptor.capture(), nowCaptor.capture())
        assertEquals(memberId, memberCaptor.value)
    }

    // ── checkAccessToken (SessionAuthenticationFilter가 매 요청마다 호출) ──

    @Test
    fun `checkAccessToken은 못 찾으면 Invalid`() {
        val hash = SecureToken.hash("unknown")
        `when`(memberSessionRepository.findByAccessTokenHash(hash)).thenReturn(null)

        val result = memberSessionService.checkAccessToken("unknown")

        assertEquals(AccessTokenCheck.Invalid, result)
    }

    @Test
    fun `checkAccessToken은 revoke된 행이면 Invalid`() {
        val rawToken = "revoked"
        `when`(memberSessionRepository.findByAccessTokenHash(SecureToken.hash(rawToken)))
            .thenReturn(session(accessTokenHash = SecureToken.hash(rawToken), revokedAt = Instant.now()))

        val result = memberSessionService.checkAccessToken(rawToken)

        assertEquals(AccessTokenCheck.Invalid, result)
    }

    @Test
    fun `checkAccessToken은 만료된 행이면 Expired`() {
        val rawToken = "expired"
        val expired = TableMemberSession(
            memberId = UUID.randomUUID(),
            accessTokenHash = SecureToken.hash(rawToken),
            refreshTokenHash = "refresh-hash",
            accessExpiresAt = Instant.now().minusSeconds(1),
            refreshExpiresAt = Instant.now().plusSeconds(3600),
            familyId = UUID.randomUUID(),
        )
        `when`(memberSessionRepository.findByAccessTokenHash(SecureToken.hash(rawToken))).thenReturn(expired)

        val result = memberSessionService.checkAccessToken(rawToken)

        assertEquals(AccessTokenCheck.Expired, result)
    }

    @Test
    fun `checkAccessToken은 유효한 행이면 memberId를 담은 Valid`() {
        val rawToken = "valid"
        val memberId = UUID.randomUUID()
        `when`(memberSessionRepository.findByAccessTokenHash(SecureToken.hash(rawToken)))
            .thenReturn(session(memberId = memberId, accessTokenHash = SecureToken.hash(rawToken)))

        val result = memberSessionService.checkAccessToken(rawToken)

        assertEquals(AccessTokenCheck.Valid(memberId), result)
    }
}
