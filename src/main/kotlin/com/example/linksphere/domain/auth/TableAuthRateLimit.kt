package com.example.linksphere.domain.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

data class AuthRateLimitId(
    val bucketKey: String = "",
    val windowStart: Instant = Instant.EPOCH,
) : Serializable

// sql/create_auth_rate_limits.sql(Phase 2)과 컬럼명 1:1 대응. bucket_key 예:
// "login-fail:<sha256(email)>", "login-fail:ip:<ip>", "signup:ip:<ip>" - 이메일은 해시로만
// 들어가 이 테이블 자체엔 PII가 남지 않는다(SQL 파일 주석 참고).
@Entity
@Table(name = "auth_rate_limits")
@IdClass(AuthRateLimitId::class)
class TableAuthRateLimit(
    @Id @Column(name = "bucket_key", nullable = false, length = 200) val bucketKey: String,
    @Id @Column(name = "window_start", nullable = false) val windowStart: Instant,
    @Column(name = "hit_count", nullable = false) var hitCount: Int = 0,
)
