package com.example.linksphere.domain.auth

import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.global.exception.InvalidTokenException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class IssuedSession(
    val accessToken: String,
    val refreshToken: String,
    val refreshExpiresInSeconds: Long,
)

/** SessionAuthenticationFilter가 access 토큰 하나를 판정한 결과. */
sealed interface AccessTokenCheck {
    data class Valid(val memberId: UUID) : AccessTokenCheck

    data object Expired : AccessTokenCheck

    data object Invalid : AccessTokenCheck
}

/**
 * 로그인 세션(access+refresh) 발급·조회·회전·폐기. JwtTokenProvider를 대체한다 - 토큰
 * 자체엔 아무 정보도 담지 않고(SecureToken.generate()는 무작위 문자열일 뿐), 이 서비스가
 * DB(member_sessions)를 갖고 매번 진위를 판정한다.
 */
@Service
@Transactional(readOnly = true)
class MemberSessionService(private val memberSessionRepository: MemberSessionRepository) {

    companion object {
        private val ACCESS_TOKEN_VALIDITY = Duration.ofHours(1)
        private val REFRESH_TOKEN_VALIDITY = Duration.ofDays(7)

        // 세 실패 경로(못 찾음·만료·재사용의심) 모두 같은 메시지를 쓴다 - "재사용 탐지됨"처럼
        // 구체적인 사유를 노출하면 탈취한 쪽에게 "들켰다"는 신호를 주게 된다(PR #42 리뷰에서
        // 발견 - 제거된 JwtException 핸들러의 "실패 사유를 그대로 노출하지 않는다" 원칙과도
        // 일관되게 맞춘다).
        private const val INVALID_REFRESH_MESSAGE = "Invalid refresh token"
    }

    /** 로그인 성공 시 새 세션(새 회전 계열)을 만든다. */
    @Transactional
    fun createSession(memberId: UUID): IssuedSession {
        val now = Instant.now()
        val rawAccess = SecureToken.generate()
        val rawRefresh = SecureToken.generate()

        memberSessionRepository.save(
            TableMemberSession(
                memberId = memberId,
                accessTokenHash = SecureToken.hash(rawAccess),
                refreshTokenHash = SecureToken.hash(rawRefresh),
                accessExpiresAt = now.plus(ACCESS_TOKEN_VALIDITY),
                refreshExpiresAt = now.plus(REFRESH_TOKEN_VALIDITY),
                familyId = UUID.randomUUID(),
            ),
        )

        return IssuedSession(rawAccess, rawRefresh, REFRESH_TOKEN_VALIDITY.seconds)
    }

    /**
     * POST /auth/refresh. 제시된 refresh 토큰을 소비하고 같은 회전 계열로 새 토큰 쌍을
     * 발급한다. refresh_expires_at은 연장하지 않고 원래 로그인 시점 값을 그대로 물려받는다
     * - 회전해도 세션의 절대 수명(7일)은 늘어나지 않는다. access_expires_at도 그 절대
     * 수명을 넘지 않도록 상한을 둔다.
     *
     * consumeRefreshIfActive의 "WHERE revoked_at IS NULL" 조건부 UPDATE로 소비 여부를
     * 원자적으로 확정한다(리포지토리 주석 참고) - 동시에 들어온 두 요청 중 하나만 이길 수
     * 있다. 지면(0행), 그 원인이 "이미 정상적으로 소비된 토큰의 재사용(탈취 의심)"이든
     * "거의 동시에 들어온 정상 요청과의 경합"이든 구분할 안전한 방법이 없으므로 둘 다 같은
     * 계열 전체를 폐기한다 - 이 앱 규모에서 두 탭이 정말 같은 순간에 refresh를 보내는
     * 경우는 드물고, 그 드문 경우 두 탭 다 로그아웃되고 다시 로그인하면 된다는 것이
     * 재사용 탐지를 우회당하는 것보다 안전하다는 판단이다.
     */
    @Transactional
    fun rotate(rawRefreshToken: String): IssuedSession {
        val current =
            memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawRefreshToken))
                ?: throw InvalidTokenException(INVALID_REFRESH_MESSAGE)

        val now = Instant.now()
        if (current.refreshExpiresAt.isBefore(now)) {
            throw InvalidTokenException(INVALID_REFRESH_MESSAGE)
        }

        val consumed = memberSessionRepository.consumeRefreshIfActive(current.id, now)
        if (consumed == 0) {
            memberSessionRepository.revokeFamily(current.familyId, now)
            throw InvalidTokenException(INVALID_REFRESH_MESSAGE)
        }

        val rawAccess = SecureToken.generate()
        val rawRefresh = SecureToken.generate()
        memberSessionRepository.save(
            TableMemberSession(
                memberId = current.memberId,
                accessTokenHash = SecureToken.hash(rawAccess),
                refreshTokenHash = SecureToken.hash(rawRefresh),
                accessExpiresAt = minOf(now.plus(ACCESS_TOKEN_VALIDITY), current.refreshExpiresAt),
                refreshExpiresAt = current.refreshExpiresAt,
                familyId = current.familyId,
            ),
        )

        val remainingSeconds = Duration.between(now, current.refreshExpiresAt).seconds.coerceAtLeast(0)
        return IssuedSession(rawAccess, rawRefresh, remainingSeconds)
    }

    /** 로그아웃(이 기기만). 쿠키가 없거나 이미 알 수 없는 값이어도 조용히 넘어간다. */
    @Transactional
    fun revokeByRefreshToken(rawRefreshToken: String?) {
        if (rawRefreshToken.isNullOrBlank()) return
        val session = memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawRefreshToken)) ?: return
        if (session.revokedAt == null) {
            session.revokedAt = Instant.now()
            memberSessionRepository.save(session)
        }
    }

    /** 로그아웃(전체 기기)·비밀번호변경·회원탈퇴에서 그 회원의 모든 세션을 끊을 때 쓴다. */
    @Transactional
    fun revokeAllForMember(memberId: UUID) {
        memberSessionRepository.revokeAllActiveForMember(memberId, Instant.now())
    }

    /** SessionAuthenticationFilter가 매 요청마다 access 토큰을 판정할 때 쓴다. */
    fun checkAccessToken(rawAccessToken: String): AccessTokenCheck {
        val session =
            memberSessionRepository.findByAccessTokenHash(SecureToken.hash(rawAccessToken))
                ?: return AccessTokenCheck.Invalid
        if (session.accessExpiresAt.isBefore(Instant.now())) return AccessTokenCheck.Expired
        // revoke는 로그아웃·재사용탐지처럼 "확실히 죽었다"뿐 아니라, 다른 탭이 refresh를
        // 돌려 같은 계열의 이전 행이 회전된 경우에도 일어난다(access·refresh를 한 행에서
        // 같이 관리하므로 분리되지 않는다). 후자라면 이 access는 원래 자연 만료 전까지
        // 정상 사용자의 유효한 토큰이므로, Invalid(즉시 로그아웃)가 아니라 Expired로
        // 응답해 FE가 공유 refresh 쿠키(이미 새 값으로 회전됨)로 조용히 회복하게 한다.
        // 진짜 로그아웃·탈취 대응인 경우 그 refresh 쿠키도 함께 죽어있어 회복 시도가
        // 실패해 결국 로그인 화면으로 수렴한다 - 보안 결과는 같고 UX만 나아진다(PR #42
        // 리뷰에서 발견: 회전 시 다른 탭의 아직 유효한 access가 즉시 로그아웃당하던 문제).
        if (session.revokedAt != null) return AccessTokenCheck.Expired
        return AccessTokenCheck.Valid(session.memberId)
    }
}
