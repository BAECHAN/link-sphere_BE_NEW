package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberDeletionRequestedEvent
import com.example.linksphere.domain.member.MemberPurgedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 회원의 탈퇴 단계가 바뀌면 그 회원의 세션을 모두 폐기한다(member의 MemberLifecycleEvents.kt).
 * 발행한 쪽(AccountDeletionService)의 트랜잭션 안에서 실행돼야 해서 트랜잭션이 없으면 실패한다(MANDATORY).
 */
@Component
class SessionMemberEventListener(private val memberSessionService: MemberSessionService) {

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onDeletionRequested(event: MemberDeletionRequestedEvent) {
        memberSessionService.revokeAllForMember(event.memberId)
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onPurged(event: MemberPurgedEvent) {
        memberSessionService.revokeAllForMember(event.memberId)
    }
}
