package com.example.linksphere.global.common

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 지금 트랜잭션이 커밋된 뒤에 할 일을 등록한다 — 롤백되면 실행하지 않는다. 스토리지 파일 삭제·메일 발송처럼 DB 밖의 부수
 * 효과를 커밋 전에 하면, 커밋이 실패했을 때 DB에는 남았는데 파일만 사라지거나 DB에 없는 토큰의 링크가 이미 나가 있다
 * (docs/BE-ARCHITECTURE.md §7-6). 트랜잭션 밖에서 부르면 TransactionSynchronizationManager가 IllegalStateException을 던진다
 * — 예전 복사본 5곳(CommentService 3·AuthService·PasswordResetService)과 같은 동작이다.
 *
 * 커밋 뒤 비동기 잡(self-invoke)은 이것이 아니라 이벤트 + @TransactionalEventListener(AFTER_COMMIT)를 쓴다(§7-5).
 */
object TransactionCallbacks {

    fun afterCommit(action: () -> Unit) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    action()
                }
            },
        )
    }
}
