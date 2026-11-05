package com.example.linksphere.domain.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// sql/create_member_action_tokens.sql(Phase 2)의 purpose 컬럼 값 - DB에 CHECK 제약은 없고
// 코드 레벨 관례다.
object MemberActionTokenPurpose {
    const val PASSWORD_RESET = "PASSWORD_RESET"
    const val EMAIL_VERIFY = "EMAIL_VERIFY"
}

// member_sessions와 같은 이유로 원문 토큰은 저장하지 않고 sha256 해시만 저장한다
// (SecureToken.hash) - DB가 새도 재사용 불가.
@Entity
@Table(name = "member_action_tokens")
class TableMemberActionToken(
    @Id @Column(name = "id", nullable = false) val id: UUID = UUID.randomUUID(),
    @Column(name = "member_id", nullable = false) val memberId: UUID,
    @Column(name = "purpose", nullable = false, length = 20) val purpose: String,
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) val tokenHash: String,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "consumed_at") var consumedAt: Instant? = null,
    @Column(name = "created_at", updatable = false) val createdAt: Instant = Instant.now(),
    // 메일 요청의 Idempotency-Key. 가입 경로의 인증 메일과 만료·사용된 키로 다시 만든 토큰은 null이다.
    @Column(name = "idempotency_key", updatable = false) val idempotencyKey: UUID? = null,
) {
    fun isActive(now: Instant): Boolean = consumedAt == null && expiresAt.isAfter(now)

    companion object {
        // 겹친 메일 요청을 가리는 유니크 인덱스(sql/add_idempotency_keys.sql)
        const val IDEMPOTENCY_INDEX = "uq_member_action_tokens_member_idempotency_key"
    }
}
