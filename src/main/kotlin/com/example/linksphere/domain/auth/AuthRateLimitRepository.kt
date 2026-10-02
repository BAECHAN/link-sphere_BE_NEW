package com.example.linksphere.domain.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

// 두 파라미터 모두 nullable인 이유: 호출부(RateLimitService)는 항상 실값만 넘긴다 - null이면
// 그대로 쿼리 오류가 난다. 그런데도 nullable로 둔 건 순전히 테스트 때문이다 - windowStart는
// Instant.now()를 윈도 경계로 내림한 값이라 테스트가 미리 정확한 값을 알 수 없어 Mockito
// any()로 검증해야 한다. Mockito 매처 규칙상 한 호출의 인자 중 하나라도 매처(any()/eq())를
// 쓰면 나머지 인자도 전부 매처로 감싸야 하므로(원시값과 매처를 섞을 수 없음), bucketKey도
// eq("literal")로 감싸야 하는데 - Kotlin non-null 파라미터를 어떤 형태로든 매처로 감싸면
// Mockito가 내부적으로 반환하는 null이 Kotlin의 파라미터 null 체크에 걸려 NPE가 나고 매처
// 스택이 오염돼 같은 JVM의 무관한 다른 테스트까지 연쇄로 깨뜨린다(MemberSessionRepository.kt
// 에서 실측 확인한 것과 동일한 문제, PR #42 참고 - 처음엔 bucketKey만 non-null로 뒀다가
// eq("literal")조차 실측으로 NPE가 재현돼 둘 다 nullable로 정정했다).
interface AuthRateLimitRepository : JpaRepository<TableAuthRateLimit, AuthRateLimitId> {

    // PostViewRepository.upsertView와 같은 INSERT ON CONFLICT DO UPDATE 선례 - 다만 덮어쓰기가
    // 아니라 누적이라 hit_count + 1. 동시 요청이 몰려도 Postgres가 행 단위로 직렬화해 증가분이
    // 유실되지 않는다.
    @Modifying
    @Query(
        value = "INSERT INTO auth_rate_limits (bucket_key, window_start, hit_count) " +
            "VALUES (:bucketKey, :windowStart, 1) " +
            "ON CONFLICT (bucket_key, window_start) DO UPDATE SET hit_count = auth_rate_limits.hit_count + 1",
        nativeQuery = true,
    )
    fun incrementHit(@Param("bucketKey") bucketKey: String?, @Param("windowStart") windowStart: Instant?)

    fun findByBucketKeyAndWindowStart(bucketKey: String?, windowStart: Instant?): TableAuthRateLimit?

    // RateLimitService.consume 전용 - incrementHit 직후 같은 트랜잭션에서 읽는다. 엔티티가 아니라
    // 숫자만 돌려받는 이유: 같은 트랜잭션에서 이 행의 엔티티를 먼저 읽어 둔 적이 있으면 JPQL 조회는
    // 영속성 컨텍스트의 옛 인스턴스를 그대로 돌려줘 방금 올린 값이 안 보인다(native 스칼라는 무관).
    @Query(
        value = "SELECT hit_count FROM auth_rate_limits WHERE bucket_key = :bucketKey AND window_start = :windowStart",
        nativeQuery = true,
    )
    fun findHitCount(@Param("bucketKey") bucketKey: String?, @Param("windowStart") windowStart: Instant?): Int?
}
