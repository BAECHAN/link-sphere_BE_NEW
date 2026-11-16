package com.example.linksphere.domain.comment

import com.example.linksphere.domain.member.MemberPurgedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 회원이 영구 삭제되면 그 회원이 누른 댓글 좋아요를 지운다(member의 MemberLifecycleEvents.kt). 댓글 자체는 남긴다 -
 * 익명화된 회원 행을 그대로 가리켜 "탈퇴한 사용자"로 보인다.
 * 발행한 쪽의 트랜잭션 안에서 실행돼야 해서 트랜잭션이 없으면 실패한다(MANDATORY).
 */
@Component
class CommentMemberEventListener(private val commentReactionRepository: CommentReactionRepository) {

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onPurged(event: MemberPurgedEvent) {
        commentReactionRepository.deleteByUserId(event.memberId)
    }
}
