package com.example.linksphere.domain.auth

import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.global.exception.InvalidTokenException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant
import java.util.UUID

/**
 * ArgumentMatchers.any()/eq()는 Kotlin **non-null** 파라미터(TableMemberSession 등)에 쓰면
 * null을 반환해 즉시 NPE가 나고, 그 예외가 Mockito의 매처 스택을 미소비 상태로 남겨 같은
 * JVM에서 도는 무관한 다른 테스트 클래스까지 연쇄로 깨뜨린다(실측 확인). save() 검증은 그
 * 문제를 피해 ArgumentCaptor(캡처만, 비교 안 함)와 concrete 값 + verifyNoMoreInteractions로
 * 한다. 반면 consumeRefreshIfActive/revokeFamily/revokeAllActiveForMember는 파라미터가
 * **nullable**(UUID?/Instant?, MemberSessionRepository.kt 참고)이라 이 문제가 없어
 * any()를 그대로 stub(양쪽 다 any()만 써서 matcher 종류를 안 섞는다)에 쓴다 - 정확히 어떤
 * 값이 넘어갔는지는 필요한 곳에서만 별도 ArgumentCaptor로 검증한다.
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
    fun `rotate는 유효한 refresh면 원자적으로 소비 처리하고 같은 family_id로 새 행을 만든다`() {
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
        // consumeRefreshIfActive가 1(성공)을 반환해야 회전이 진행된다 - 0이면(레이스에서
        // 졌거나 이미 revoke됨) 다른 분기로 빠진다(별도 테스트).
        `when`(memberSessionRepository.consumeRefreshIfActive(any(), any())).thenReturn(1)

        val issued = memberSessionService.rotate(rawToken)

        val idCaptor = ArgumentCaptor.forClass(UUID::class.java)
        verify(memberSessionRepository).consumeRefreshIfActive(idCaptor.capture(), any())
        assertEquals(current.id, idCaptor.value)

        val newSessionCaptor = ArgumentCaptor.forClass(TableMemberSession::class.java)
        verify(memberSessionRepository).save(newSessionCaptor.capture())
        val newSession = newSessionCaptor.value

        assertEquals(memberId, newSession.memberId)
        assertEquals(familyId, newSession.familyId)
        // 절대 수명은 연장되지 않는다 - 원래 refreshExpiresAt을 그대로 물려받는다.
        assertEquals(refreshExpiresAt, newSession.refreshExpiresAt)
        // access 만료도 그 절대 수명을 넘지 않는다(이 케이스는 1000초 여유가 있어 1시간보다
        // 작으므로 실제로 캡을 맞고 refreshExpiresAt과 같아진다).
        assertEquals(refreshExpiresAt, newSession.accessExpiresAt)

        assertNotEquals(0L, issued.refreshExpiresInSeconds)
        // revokeFamily는 호출되지 않는다 - 정상 회전이지 재사용/레이스가 아니다.
        verify(memberSessionRepository, never()).revokeFamily(any(), any())
        // 유예 판정은 소비에 졌을 때만 한다.
        verify(memberSessionRepository, never()).findRevokedAtById(any())
    }

    @Test
    fun `rotate는 회전된 지 유예(10초)가 지난 refresh가 다시 오면 같은 계열 전체를 폐기하고 예외를 던진다`() {
        val familyId = UUID.randomUUID()
        val rawToken = "reused-token"
        val current = session(
            refreshTokenHash = SecureToken.hash(rawToken),
            familyId = familyId,
        )
        `when`(memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawToken))).thenReturn(current)
        // 0 = 조건부 UPDATE가 아무 행도 못 건드림 - 이미 revoke돼 있었다.
        `when`(memberSessionRepository.consumeRefreshIfActive(any(), any())).thenReturn(0)
        `when`(memberSessionRepository.findRevokedAtById(any())).thenReturn(Instant.now().minusSeconds(11))

        assertThrows(InvalidTokenException::class.java) {
            memberSessionService.rotate(rawToken)
        }

        val familyCaptor = ArgumentCaptor.forClass(UUID::class.java)
        verify(memberSessionRepository).revokeFamily(familyCaptor.capture(), any())
        assertEquals(familyId, familyCaptor.value)

        // 소비 실패로 끝나므로 새 세션은 저장되지 않는다. 유예 밖이라 계열 생존 여부도 묻지 않는다.
        verify(memberSessionRepository, never()).save(any())
        verify(memberSessionRepository, never()).countActiveInFamily(any(), any())
    }

    @Test
    fun `rotate는 유예 안에 다시 온 refresh면(여러 탭 동시 갱신) 계열을 폐기하지 않고 같은 계열로 새 행을 만든다`() {
        val familyId = UUID.randomUUID()
        val memberId = UUID.randomUUID()
        val refreshExpiresAt = Instant.now().plusSeconds(1000)
        val rawToken = "raced-token"
        // 처음 읽은 엔티티는 이긴 쪽 커밋 전 값이라 revokedAt이 null이다 - 판정은 이 값이 아니라
        // DB에서 다시 읽은 값(findRevokedAtById)으로 해야 한다.
        val current = session(
            memberId = memberId,
            refreshTokenHash = SecureToken.hash(rawToken),
            refreshExpiresAt = refreshExpiresAt,
            familyId = familyId,
        )
        `when`(memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawToken))).thenReturn(current)
        `when`(memberSessionRepository.consumeRefreshIfActive(any(), any())).thenReturn(0)
        `when`(memberSessionRepository.findRevokedAtById(any())).thenReturn(Instant.now().minusSeconds(3))
        `when`(memberSessionRepository.countActiveInFamily(any(), any())).thenReturn(1L)

        val issued = memberSessionService.rotate(rawToken)

        val newSessionCaptor = ArgumentCaptor.forClass(TableMemberSession::class.java)
        verify(memberSessionRepository).save(newSessionCaptor.capture())
        val newSession = newSessionCaptor.value

        assertEquals(memberId, newSession.memberId)
        assertEquals(familyId, newSession.familyId)
        // 정상 회전과 같은 만료 규칙 - 절대 수명을 물려받고 access도 그 수명을 넘지 않는다.
        assertEquals(refreshExpiresAt, newSession.refreshExpiresAt)
        assertEquals(refreshExpiresAt, newSession.accessExpiresAt)
        assertNotEquals(0L, issued.refreshExpiresInSeconds)
        verify(memberSessionRepository, never()).revokeFamily(any(), any())
    }

    @Test
    fun `rotate는 유예 안이어도 계열이 이미 폐기됐으면(로그아웃 등) 계열 폐기와 예외로 끝난다`() {
        val rawToken = "token-after-logout"
        val current = session(refreshTokenHash = SecureToken.hash(rawToken))
        `when`(memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawToken))).thenReturn(current)
        `when`(memberSessionRepository.consumeRefreshIfActive(any(), any())).thenReturn(0)
        `when`(memberSessionRepository.findRevokedAtById(any())).thenReturn(Instant.now().minusSeconds(3))
        `when`(memberSessionRepository.countActiveInFamily(any(), any())).thenReturn(0L)

        assertThrows(InvalidTokenException::class.java) {
            memberSessionService.rotate(rawToken)
        }

        verify(memberSessionRepository).revokeFamily(any(), any())
        verify(memberSessionRepository, never()).save(any())
    }

    @Test
    fun `rotate는 소비에 졌는데 revokedAt이 비어 있으면 재사용으로 보고 계열을 폐기한다`() {
        val rawToken = "unknown-state-token"
        val current = session(refreshTokenHash = SecureToken.hash(rawToken))
        `when`(memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawToken))).thenReturn(current)
        `when`(memberSessionRepository.consumeRefreshIfActive(any(), any())).thenReturn(0)
        `when`(memberSessionRepository.findRevokedAtById(any())).thenReturn(null)

        assertThrows(InvalidTokenException::class.java) {
            memberSessionService.rotate(rawToken)
        }

        verify(memberSessionRepository).revokeFamily(any(), any())
        verify(memberSessionRepository, never()).save(any())
    }

    @Test
    fun `rotate는 revokedAt이 now보다 조금 미래여도(락 대기·서버 간 시계 오차) 유예로 본다`() {
        val rawToken = "clock-skew-token"
        val current = session(refreshTokenHash = SecureToken.hash(rawToken))
        `when`(memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawToken))).thenReturn(current)
        `when`(memberSessionRepository.consumeRefreshIfActive(any(), any())).thenReturn(0)
        `when`(memberSessionRepository.findRevokedAtById(any())).thenReturn(Instant.now().plusMillis(500))
        `when`(memberSessionRepository.countActiveInFamily(any(), any())).thenReturn(1L)

        memberSessionService.rotate(rawToken)

        verify(memberSessionRepository).save(ArgumentCaptor.forClass(TableMemberSession::class.java).capture())
        verify(memberSessionRepository, never()).revokeFamily(any(), any())
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
    fun `revokeByRefreshToken은 찾으면 그 행 하나가 아니라 같은 계열 전체를 폐기한다`() {
        // 회전 유예로 한 계열에 활성 행이 둘 이상일 수 있다 - 행 하나만 폐기하면 나머지 행의
        // access(최대 1시간)와 FCM 생존 판정이 로그아웃 뒤에도 남는다.
        val rawToken = "some-token"
        val familyId = UUID.randomUUID()
        val target = session(refreshTokenHash = SecureToken.hash(rawToken), familyId = familyId)
        `when`(memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawToken))).thenReturn(target)

        memberSessionService.revokeByRefreshToken(rawToken)

        val familyCaptor = ArgumentCaptor.forClass(UUID::class.java)
        verify(memberSessionRepository).revokeActiveInFamily(familyCaptor.capture(), any())
        assertEquals(familyId, familyCaptor.value)
        verify(memberSessionRepository, never()).save(any())
        verify(memberSessionRepository, never()).revokeFamily(any(), any())
    }

    @Test
    fun `revokeByRefreshToken은 이미 revoke된 행이어도 같은 계열의 남은 활성 행을 폐기한다`() {
        // 회전 응답을 못 받은 브라우저는 이미 회전된 행을 가리키는 쿠키로 로그아웃할 수 있다 -
        // 그 행만 보고 멈추면 같은 계열의 살아있는 후속 행이 남는다.
        val rawToken = "some-token"
        val familyId = UUID.randomUUID()
        val alreadyRevoked =
            session(refreshTokenHash = SecureToken.hash(rawToken), revokedAt = Instant.now(), familyId = familyId)
        `when`(memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawToken))).thenReturn(alreadyRevoked)

        memberSessionService.revokeByRefreshToken(rawToken)

        val familyCaptor = ArgumentCaptor.forClass(UUID::class.java)
        verify(memberSessionRepository).revokeActiveInFamily(familyCaptor.capture(), any())
        assertEquals(familyId, familyCaptor.value)
        verify(memberSessionRepository, never()).save(any())
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
    fun `checkAccessToken은 revoke됐지만 자연 만료 전이면 Expired(회전한 다른 탭 대비, Invalid 아님)`() {
        // 회전은 이전 행의 revokedAt을 채우지만 access 자체는 원래 accessExpiresAt까지
        // 유효했어야 한다 - 여기서 Invalid를 주면 다른 탭이 즉시 로그아웃된다(PR #42 리뷰).
        // Expired를 주면 FE가 공유 refresh 쿠키로 조용히 회복을 시도한다.
        val rawToken = "revoked-but-not-expired"
        `when`(memberSessionRepository.findByAccessTokenHash(SecureToken.hash(rawToken)))
            .thenReturn(session(accessTokenHash = SecureToken.hash(rawToken), revokedAt = Instant.now()))

        val result = memberSessionService.checkAccessToken(rawToken)

        assertEquals(AccessTokenCheck.Expired, result)
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
    fun `checkAccessToken은 유효한 행이면 memberId와 familyId를 담은 Valid`() {
        val rawToken = "valid"
        val memberId = UUID.randomUUID()
        val familyId = UUID.randomUUID()
        `when`(memberSessionRepository.findByAccessTokenHash(SecureToken.hash(rawToken)))
            .thenReturn(session(memberId = memberId, accessTokenHash = SecureToken.hash(rawToken), familyId = familyId))

        val result = memberSessionService.checkAccessToken(rawToken)

        assertEquals(AccessTokenCheck.Valid(memberId, familyId), result)
    }
}
