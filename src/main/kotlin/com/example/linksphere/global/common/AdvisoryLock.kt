package com.example.linksphere.global.common

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 트랜잭션이 끝날 때까지 쥐는 Postgres advisory lock. "같은 URL을 1시간 안에"처럼 유니크 인덱스로 표현할 수 없는 조건을
 * 확인하고 쓰는 동안 같은 키의 요청을 한 줄로 세운다. 다른 키는 서로 기다리지 않고, Lambda가 죽어 연결이 끊겨도 잠금은 풀린다.
 *
 * 세션 수준 잠금(pg_advisory_lock)은 쓰지 않는다 - 운영은 Supavisor 트랜잭션 모드(6543)라 서버 커넥션을 돌려 쓰므로 세션 상태가
 * 다른 요청에 남는다. 트랜잭션 수준 잠금은 커밋·롤백 때 자동으로 풀린다(docs/BE-ARCHITECTURE.md §7-14).
 */
@Component
class AdvisoryLock(private val entityManager: EntityManager) {

    // 트랜잭션 밖에서 부르면 잠금이 곧바로 풀려 아무것도 막지 못하므로 MANDATORY로 막는다
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockForTransaction(key: String) {
        entityManager
            .createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .setParameter("key", key)
            .singleResult
    }
}
