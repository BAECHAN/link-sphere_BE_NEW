package com.example.linksphere.domain.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface MemberSessionRepository : JpaRepository<TableMemberSession, UUID> {
    fun findByAccessTokenHash(accessTokenHash: String): TableMemberSession?

    fun findByRefreshTokenHash(refreshTokenHash: String): TableMemberSession?

    // 재사용 탐지(MemberSessionService.rotate) 시 같은 회전 계열 전체를 폐기한다. 정상
    // 흐름에선 계열당 활성 행이 하나뿐이지만, 방어적으로 "아직 안 지워진" 것 전부를 막는다.
    //
    // 파라미터가 nullable(UUID?/Instant?)인 이유: 호출부(MemberSessionService)는 항상 실값만
    // 넘긴다 - JPA @Query 파라미터라 null이 오면 그대로 SQL 오류가 난다. 그런데도 nullable로
    // 둔 건 순전히 테스트 때문이다 - Kotlin에서 선언한 non-null 인터페이스 메서드를
    // ArgumentCaptor/any()로 검증하면 Mockito가 내부적으로 반환하는 null이 Kotlin의
    // 파라미터 null 체크에 걸려 즉시 NPE가 나고, 그 예외가 Mockito 매처 스택을 미소비
    // 상태로 남겨 같은 JVM에서 도는 무관한 다른 테스트 클래스까지 연쇄로 깨뜨린다(실측
    // 확인 - MemberSessionServiceTest 상단 주석 참고). save() 등 Spring Data가 제공하는
    // Java 인터페이스 메서드는 이 문제가 없다(플랫폼 타입이라 Kotlin이 체크를 안 넣음).
    @Modifying
    @Query("UPDATE TableMemberSession s SET s.revokedAt = :now WHERE s.familyId = :familyId AND s.revokedAt IS NULL")
    fun revokeFamily(@Param("familyId") familyId: UUID?, @Param("now") now: Instant?): Int

    // 로그아웃(전체 기기)·비밀번호변경·회원탈퇴에서 쓴다. 대상 회원의 모든 세션을 한
    // 쿼리로 끊는다 - 행을 하나씩 불러와 save()하지 않는다(잠재적으로 여러 기기 분량).
    // 파라미터가 nullable인 이유는 위 revokeFamily와 동일(테스트 전용 완화).
    @Modifying
    @Query("UPDATE TableMemberSession s SET s.revokedAt = :now WHERE s.memberId = :memberId AND s.revokedAt IS NULL")
    fun revokeAllActiveForMember(@Param("memberId") memberId: UUID?, @Param("now") now: Instant?): Int
}
