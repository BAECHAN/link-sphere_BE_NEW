package com.example.linksphere.domain.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 아래 커스텀 메서드들의 UUID?/Instant? 파라미터가 nullable인 이유: 호출부는 항상 실값만
 * 넘긴다 - JPA @Query 파라미터라 null이 오면 그대로 SQL 오류가 난다. 그런데도 nullable로
 * 둔 건 순전히 테스트 때문이다 - Kotlin에서 선언한 non-null 인터페이스 메서드를
 * ArgumentCaptor/any()로 검증하면 Mockito가 내부적으로 반환하는 null이 Kotlin의 파라미터
 * null 체크에 걸려 즉시 NPE가 나고, 그 예외가 Mockito 매처 스택을 미소비 상태로 남겨 같은
 * JVM에서 도는 무관한 다른 테스트 클래스까지 연쇄로 깨뜨린다(실측 확인 -
 * MemberSessionServiceTest 상단 주석 참고). save() 등 Spring Data가 제공하는 Java 인터페이스
 * 메서드는 이 문제가 없다(플랫폼 타입이라 Kotlin이 체크를 안 넣음).
 */
interface MemberSessionRepository : JpaRepository<TableMemberSession, UUID> {
    fun findByAccessTokenHash(accessTokenHash: String): TableMemberSession?

    fun findByRefreshTokenHash(refreshTokenHash: String): TableMemberSession?

    // MemberSessionService.rotate가 이 행을 "내가 먼저 소비했는지" 원자적으로 확정하는 데
    // 쓴다 - findByRefreshTokenHash로 읽은 뒤 조건 없이 save()하면, 동시에 들어온 두 요청이
    // 둘 다 "아직 안 revoke됐다"를 보고 둘 다 새 세션을 발급받는 레이스가 생긴다(재사용
    // 탐지를 그대로 우회당함, PR #42 리뷰에서 실측 확인). WHERE revoked_at IS NULL 조건이
    // 있는 이 UPDATE 자체가 "먼저 실행된 요청만 1행을 건드린다"는 DB 레벨 락 역할을 한다 -
    // 영향받은 행 수(0 또는 1)로 승패를 가른다.
    @Modifying
    @Query("UPDATE TableMemberSession s SET s.revokedAt = :now WHERE s.id = :id AND s.revokedAt IS NULL AND s.refreshExpiresAt > :now")
    fun consumeRefreshIfActive(@Param("id") id: UUID?, @Param("now") now: Instant?): Int

    // 재사용 탐지(MemberSessionService.rotate) 시 같은 회전 계열 전체를 폐기한다. 정상
    // 흐름에선 계열당 활성 행이 하나뿐이지만, 방어적으로 "아직 안 지워진" 것 전부를 막는다.
    //
    // REQUIRES_NEW인 이유: 호출부(rotate)는 이 폐기를 실행한 직후 InvalidTokenException을
    // 던져 요청을 401로 끝낸다. 같은 트랜잭션 안에서라면 그 RuntimeException 때문에
    // 트랜잭션 전체가 롤백되어 방금 실행한 이 UPDATE까지 함께 취소된다 - 재사용 탐지
    // "기능"이 겉으로는 401을 뱉지만 실제로는 아무것도 폐기하지 않는 상태가 된다(PR #42
    // 리뷰에서 실측 확인). 별도 트랜잭션으로 분리해 이 UPDATE만 독립적으로 즉시 커밋되게
    // 한다. Spring Data 리포지토리는 그 자체가 별도 프록시 빈이라(self-invocation이 아님)
    // 이 애노테이션이 정상적으로 적용된다.
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE TableMemberSession s SET s.revokedAt = :now WHERE s.familyId = :familyId AND s.revokedAt IS NULL")
    fun revokeFamily(@Param("familyId") familyId: UUID?, @Param("now") now: Instant?): Int

    // 로그아웃(전체 기기)·비밀번호변경·회원탈퇴에서 쓴다. 대상 회원의 모든 세션을 한
    // 쿼리로 끊는다 - 행을 하나씩 불러와 save()하지 않는다(잠재적으로 여러 기기 분량).
    @Modifying
    @Query("UPDATE TableMemberSession s SET s.revokedAt = :now WHERE s.memberId = :memberId AND s.revokedAt IS NULL")
    fun revokeAllActiveForMember(@Param("memberId") memberId: UUID?, @Param("now") now: Instant?): Int
}
