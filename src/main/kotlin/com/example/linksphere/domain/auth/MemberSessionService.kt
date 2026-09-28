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
     * POST /auth/refresh. 제시된 refresh 토큰을 소비(revoke)하고 같은 회전 계열로 새 토큰
     * 쌍을 발급한다. refresh_expires_at은 연장하지 않고 원래 로그인 시점 값을 그대로
     * 물려받는다 - 회전해도 세션의 절대 수명(7일)은 늘어나지 않는다.
     *
     * 이미 revoke된 refresh가 다시 제시되면 탈취 후 재사용으로 간주해 같은 계열 전체를
     * 폐기한다. 다만 이 구현은 같은 refresh를 정상적으로 "거의 동시에" 두 탭이 보내는
     * 경합(멀티탭 레이스)까지는 구분하지 않는다 - 그 경우도 재사용으로 취급되어 뒤에 도착한
     * 탭이 로그아웃된다. 이 앱 규모에서 흔치 않은 경우라 유예창 없이 단순하게 구현했다.
     */
    @Transactional
    fun rotate(rawRefreshToken: String): IssuedSession {
        val current =
            memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawRefreshToken))
                ?: throw InvalidTokenException("Invalid refresh token")

        val now = Instant.now()

        if (current.revokedAt != null) {
            memberSessionRepository.revokeFamily(current.familyId, now)
            throw InvalidTokenException("Refresh token reuse detected")
        }
        if (current.refreshExpiresAt.isBefore(now)) {
            throw InvalidTokenException("Refresh token expired")
        }

        current.revokedAt = now
        memberSessionRepository.save(current)

        val rawAccess = SecureToken.generate()
        val rawRefresh = SecureToken.generate()
        memberSessionRepository.save(
            TableMemberSession(
                memberId = current.memberId,
                accessTokenHash = SecureToken.hash(rawAccess),
                refreshTokenHash = SecureToken.hash(rawRefresh),
                accessExpiresAt = now.plus(ACCESS_TOKEN_VALIDITY),
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
        if (session.revokedAt != null) return AccessTokenCheck.Invalid
        if (session.accessExpiresAt.isBefore(Instant.now())) return AccessTokenCheck.Expired
        return AccessTokenCheck.Valid(session.memberId)
    }
}
