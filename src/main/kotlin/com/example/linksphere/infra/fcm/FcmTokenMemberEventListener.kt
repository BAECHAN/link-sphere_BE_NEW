package com.example.linksphere.infra.fcm

import com.example.linksphere.domain.member.MemberDeletionRequestedEvent
import com.example.linksphere.domain.member.MemberPurgedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 회원의 탈퇴 단계가 바뀌면 그 회원의 알림 토큰을 지운다(member의 MemberLifecycleEvents.kt). 신청 때 바로 지우는 이유는
 * 유예 중 푸시가 계속 오면 안 되기 때문이다 - 로그인으로 복구되면 FE가 다시 등록한다.
 * 발행한 쪽의 트랜잭션 안에서 실행돼야 해서 트랜잭션이 없으면 실패한다(MANDATORY).
 */
@Component
class FcmTokenMemberEventListener(private val fcmTokenRepository: FcmTokenRepository) {

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onDeletionRequested(event: MemberDeletionRequestedEvent) {
        fcmTokenRepository.deleteByUserId(event.memberId)
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onPurged(event: MemberPurgedEvent) {
        fcmTokenRepository.deleteByUserId(event.memberId)
    }
}
