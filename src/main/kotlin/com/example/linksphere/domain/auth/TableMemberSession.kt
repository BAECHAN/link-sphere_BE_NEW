package com.example.linksphere.domain.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 로그인 세션 한 건 = 한 행. access·refresh 토큰 원문은 저장하지 않고 sha256 해시만
 * 저장한다(SecureToken 참고). JWT를 대체한다 - 매 요청마다 이 행을 조회해야 로그아웃이
 * 실제로 즉시 반영된다(docs/plans/2026-09-28-auth-hardening.md의 "왜 바꾸나" 참고).
 *
 * family_id: 회전 계열 식별자. /auth/refresh는 이 행을 revoke하고 같은 family_id로 새
 * 행을 만든다(MemberSessionService.rotate). 이미 revoke된 refresh_token_hash가 다시
 * 제시되면(탈취 후 재사용 의심) 같은 family_id의 행을 전부 revoke한다.
 */
@Entity
@Table(name = "member_sessions")
class TableMemberSession(
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    val id: UUID = UUID.randomUUID(),
    @Column(name = "member_id", nullable = false) val memberId: UUID,
    @Column(name = "access_token_hash", nullable = false, unique = true) var accessTokenHash: String,
    @Column(name = "refresh_token_hash", nullable = false, unique = true) var refreshTokenHash: String,
    @Column(name = "access_expires_at", nullable = false) var accessExpiresAt: Instant,
    @Column(name = "refresh_expires_at", nullable = false) var refreshExpiresAt: Instant,
    @Column(name = "revoked_at") var revokedAt: Instant? = null,
    @Column(name = "family_id", nullable = false) val familyId: UUID,
    @Column(name = "created_at", updatable = false) val createdAt: Instant = Instant.now(),
)
