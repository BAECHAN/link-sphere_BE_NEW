package com.example.linksphere.domain.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

// id·now가 nullable인 이유: 호출부는 항상 실값만 넘긴다 - MemberSessionRepository.kt의
// consumeRefreshIfActive와 완전히 같은 이유다(Kotlin non-null 파라미터를 Mockito
// any()/eq()로 감싸면 Kotlin의 파라미터 null 체크에 걸려 NPE가 나고 매처 스택이 오염돼
// 무관한 다른 테스트까지 연쇄로 깨진다, PR #42·#44에서 실측 확인). 테스트가 정확한 Instant
// 값을 미리 알 수 없어 any()가 필요해 nullable로 둔다.
interface MemberActionTokenRepository : JpaRepository<TableMemberActionToken, UUID> {
    fun findByTokenHash(tokenHash: String): TableMemberActionToken?

    // 재사용(이미 소비됨)·동시 확인 요청을 한 번에 막는다 - WHERE consumed_at IS NULL 조건이
    // 있는 이 UPDATE 자체가 "먼저 실행된 요청만 1행을 건드린다"는 DB 레벨 락 역할을 한다.
    @Modifying
    @Query(
        "UPDATE TableMemberActionToken t SET t.consumedAt = :now " +
            "WHERE t.id = :id AND t.consumedAt IS NULL AND t.expiresAt > :now",
    )
    fun consumeIfActive(@Param("id") id: UUID?, @Param("now") now: Instant?): Int
}
