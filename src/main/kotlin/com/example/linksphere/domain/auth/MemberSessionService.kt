package com.example.linksphere.domain.auth

import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.global.exception.InvalidTokenException
import com.example.linksphere.global.exception.SessionMismatchException
import org.slf4j.LoggerFactory
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
    // familyId: 이 access 토큰이 속한 세션의 회전 계열. FcmTokenController가 토큰 등록 시
    // 이 값을 fcm_tokens.session_family_id에 실어, 나중에 이 세션이 죽으면(로그아웃·
    // 재사용탐지·비밀번호변경) 그 기기의 FCM 토큰도 함께 무효화되게 한다.
    data class Valid(val memberId: UUID, val familyId: UUID) : AccessTokenCheck

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
    private val logger = LoggerFactory.getLogger(MemberSessionService::class.java)

    companion object {
        private val ACCESS_TOKEN_VALIDITY = Duration.ofHours(1)
        private val REFRESH_TOKEN_VALIDITY = Duration.ofDays(7)

        // 방금 회전된 refresh가 이 시간 안에 다시 오면 재사용 탐지 대신 새 토큰 쌍을 준다 -
        // 같은 브라우저의 여러 탭이 같은 refresh 쿠키를 거의 동시에 제시하는 경우(새 탭 여러 개를
        // 연달아 열기 등). 10초는 Supabase Auth의 reuse interval 기본값이다(Okta grace period
        // 기본 30초, Auth0 leeway 기본 꺼짐 - docs/plans/2026-10-12-refresh-reuse-leeway.md).
        private val REUSE_LEEWAY = Duration.ofSeconds(10)

        // 세 실패 경로(못 찾음·만료·재사용의심) 모두 같은 메시지를 쓴다 - "재사용 탐지됨"처럼
        // 구체적인 사유를 노출하면 탈취한 쪽에게 "들켰다"는 신호를 주게 된다(PR #42 리뷰에서
        // 발견 - 제거된 JwtException 핸들러의 "실패 사유를 그대로 노출하지 않는다" 원칙과도
        // 일관되게 맞춘다). 회원 불일치(SessionMismatchException)는 따로 알리지만 이 원칙과
        // 충돌하지 않는다 - 회전·재사용 판정을 모두 통과해 "발급됐을" 요청에서만 나온다(assertSameMember).
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
     * 있다. 지면(0행), 그 행이 회전으로 소비된 지 REUSE_LEEWAY 안이고 계열이 아직 살아있으면
     * "거의 동시에 들어온 정상 요청과의 경합"으로 보고 같은 계열로 새 토큰 쌍을 하나 더 준다.
     * 그 밖이면 "이미 소비된 토큰의 재사용(탈취 의심)"으로 보고 계열 전체를 폐기한다.
     *
     * "최근 revoke + 계열 생존"이 곧 "방금 회전됨"인 이유: 로그아웃·전체 로그아웃·비밀번호
     * 변경·재사용 탐지는 모두 계열(또는 회원) 단위로 폐기하므로, 계열을 살려둔 채 행 하나만
     * revoke하는 경로는 회전뿐이다(revokeByRefreshToken 참고).
     *
     * 감수하는 것: 정상 회전 직후 유예 안에 탈취된 직전 토큰이 쓰이면 계열이 두 갈래가 되고,
     * 이후 양쪽 다 자기 최신 토큰만 쓰므로 재사용 탐지 없이 절대 수명까지 갈 수 있다 -
     * Okta·Auth0·Supabase의 유예도 같은 성질이다. 로그아웃(계열)·전체 로그아웃·비밀번호
     * 변경은 두 갈래를 모두 끊는다.
     *
     * @param accessTokenHint refresh 요청의 X-Access-Token(FE가 원 요청이 보낸 토큰을 싣는다). 있으면 발급 직전에
     *   그 토큰의 회원이 이 refresh의 회원과 같은지 확인한다(assertSameMember).
     */
    @Transactional
    fun rotate(rawRefreshToken: String, accessTokenHint: String? = null): IssuedSession {
        val current =
            memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawRefreshToken))
                ?: throw InvalidTokenException(INVALID_REFRESH_MESSAGE)

        val now = Instant.now()
        if (current.refreshExpiresAt.isBefore(now)) {
            throw InvalidTokenException(INVALID_REFRESH_MESSAGE)
        }

        val consumed = memberSessionRepository.consumeRefreshIfActive(current.id, now)
        if (consumed == 0) {
            // 판정은 읽기만 한다 - 아래 revokeFamily는 REQUIRES_NEW라, 이 트랜잭션이 같은 행에
            // 쓰기 락을 쥔 채 부르면 DB가 감지하지 못하는 교착이 된다.
            val revokedAt = memberSessionRepository.findRevokedAtById(current.id)
            if (revokedAt == null || !isReuseWithinLeeway(revokedAt, current.familyId, now)) {
                logger.warn(
                    "[MemberSession] refresh 재사용 탐지 - 계열 폐기(familyId=${current.familyId}, " +
                        "sinceRevokeMs=${revokedAt?.let { Duration.between(it, now).toMillis() }})",
                )
                memberSessionRepository.revokeFamily(current.familyId, now)
                throw InvalidTokenException(INVALID_REFRESH_MESSAGE)
            }

            logger.info(
                "[MemberSession] refresh 유예 안 재사용 - 같은 계열로 발급(familyId=${current.familyId}, " +
                    "sinceRevokeMs=${Duration.between(revokedAt, now).toMillis()})",
            )
        }

        assertSameMember(current, accessTokenHint)
        return issueInFamily(current, now)
    }

    /**
     * refresh에 실린 access 토큰의 회원이 이 refresh의 회원과 다르면 발급하지 않는다 - 같은 브라우저의 다른 탭에서
     * 다른 계정으로 로그인해 공유 쿠키가 바뀐 경우다. 그대로 발급하면 FE가 원 요청을 다른 계정으로 재실행한다
     * (FE docs/AUTH.md §8-B "쿠키 주인이 다른 회원이라 갱신이 거절될 때").
     *
     * 회전·재사용 판정을 마친 뒤 발급 직전에 본다 - 소비 앞에서 보면 이미 회전된(탈취 의심) refresh에 다른 계정
     * 토큰을 붙여 재사용 탐지(revokeFamily)를 피하는 길이 되고, "그 refresh가 아직 살아 있다"는 정보도 샌다. 예외는
     * 이 트랜잭션을 롤백해 앞의 consumeRefreshIfActive도 되돌린다(쿠키는 계속 유효하다).
     *
     * 계열이 아니라 회원을 비교한다 - 같은 계정의 재로그인·비밀번호 변경은 계열이 바뀐다. 힌트가 없거나 모르는
     * 토큰이면 건너뛴다(새로고침 직후 refresh에는 토큰이 없다) - 헤더를 빼면 우회되므로 보안 경계가 아니라 정상
     * 클라이언트를 위한 정합성 확인이다. 로그에는 쿠키 쪽 계열만 남긴다 - 힌트 쪽 회원을 남기면 두 계정이 한
     * 브라우저를 쓴다는 연결 정보가 된다.
     */
    private fun assertSameMember(current: TableMemberSession, accessTokenHint: String?) {
        if (accessTokenHint.isNullOrBlank()) return
        val hinted = memberSessionRepository.findByAccessTokenHash(SecureToken.hash(accessTokenHint)) ?: return
        if (hinted.memberId == current.memberId) return

        logger.info("[MemberSession] refresh 회원 불일치 - 발급하지 않음(familyId=${current.familyId})")
        throw SessionMismatchException()
    }

    // 한쪽 경계만 본다 - 진 요청의 now는 락 대기 전에 찍히고 Lambda 인스턴스 간 시계 오차도 있어
    // revokedAt이 now보다 미래일 수 있다.
    private fun isReuseWithinLeeway(revokedAt: Instant, familyId: UUID, now: Instant): Boolean {
        if (revokedAt.isBefore(now.minus(REUSE_LEEWAY))) {
            return false
        }
        return memberSessionRepository.countActiveInFamily(familyId, now) > 0
    }

    /** current와 같은 회전 계열로 새 토큰 쌍을 발급한다(정상 회전·유예 발급 공용). */
    private fun issueInFamily(current: TableMemberSession, now: Instant): IssuedSession {
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

    /**
     * 로그아웃(이 기기만). 쿠키가 없거나 이미 알 수 없는 값이어도 조용히 넘어간다.
     *
     * 쿠키가 가리키는 행 하나가 아니라 그 계열(= 이 브라우저의 로그인) 전체를 폐기한다 - 회전
     * 유예로 한 계열에 활성 행이 둘 이상일 수 있고, 회전 응답을 못 받은 브라우저는 이미 회전된
     * 행을 가리키는 쿠키로 로그아웃할 수 있다. 다른 기기는 계열이 달라 영향이 없다.
     */
    @Transactional
    fun revokeByRefreshToken(rawRefreshToken: String?) {
        if (rawRefreshToken.isNullOrBlank()) return
        val session = memberSessionRepository.findByRefreshTokenHash(SecureToken.hash(rawRefreshToken)) ?: return
        memberSessionRepository.revokeActiveInFamily(session.familyId, Instant.now())
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
        return AccessTokenCheck.Valid(session.memberId, session.familyId)
    }
}
