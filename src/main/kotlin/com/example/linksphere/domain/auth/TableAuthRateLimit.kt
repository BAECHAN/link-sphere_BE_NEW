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
// "login-fail:<sha256(email)>", "login-fail:ip:<ip>", "signup:ip:<ip>" - 이메일은 솔트 없는 해시로,
// IP는 원문으로 들어가므로 개인정보로 다룬다. 끝난 창의 행은 RateLimitService.purgeExpired가 지운다.
@Entity
@Table(name = "auth_rate_limits")
@IdClass(AuthRateLimitId::class)
class TableAuthRateLimit(
    @Id @Column(name = "bucket_key", nullable = false, length = 200) val bucketKey: String,
    @Id @Column(name = "window_start", nullable = false) val windowStart: Instant,
    @Column(name = "hit_count", nullable = false) var hitCount: Int = 0,
)
