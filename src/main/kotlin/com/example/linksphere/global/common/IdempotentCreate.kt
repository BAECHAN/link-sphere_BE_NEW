package com.example.linksphere.global.common

import org.springframework.dao.DataIntegrityViolationException

/**
 * Idempotency-Key를 행에 저장하는 등록 요청의 공통 흐름(docs/BE-ARCHITECTURE.md §7-14). 같은 키로 겹친 요청은 뒤쪽 INSERT가
 * 유니크 인덱스에서 기다렸다가 위반으로 끝나고, Postgres는 그 트랜잭션을 중단시킨다. 그래서 반드시 @Transactional 경계 밖
 * (컨트롤러)에서 부른다 - 커밋 시점에 난 위반도 경계 밖에서 잡히고, 재조회는 새 트랜잭션으로 돈다. 위반한 쪽은 커밋하지 못하므로
 * AFTER_COMMIT 이벤트와 커밋 뒤 메일이 나가지 않는다.
 *
 * 다른 제약 위반(FK 등)을 재전송으로 오해하지 않도록 인덱스 이름으로 가린다(MemberService.signup과 같은 방식).
 */
object IdempotentCreate {

    /** 이미 처리된 키면 그 결과를, 아니면 새로 만든 결과를, 겹친 요청에 졌으면 이긴 쪽 결과를 돌려준다. */
    fun <T : Any> run(index: String, find: () -> T?, create: () -> T): T {
        find()?.let { return it }

        return try {
            create()
        } catch (e: DataIntegrityViolationException) {
            if (!violates(e, index)) {
                throw e
            }

            find() ?: throw e
        }
    }

    /** 돌려줄 결과가 없는 요청용(메일 요청) - 겹친 요청에 지면 이긴 쪽이 이미 처리했으므로 조용히 끝낸다. */
    fun ignoringLoss(index: String, create: () -> Unit) {
        try {
            create()
        } catch (e: DataIntegrityViolationException) {
            if (!violates(e, index)) {
                throw e
            }
        }
    }

    private fun violates(e: DataIntegrityViolationException, index: String): Boolean = e.mostSpecificCause.message?.contains(index) == true
}
