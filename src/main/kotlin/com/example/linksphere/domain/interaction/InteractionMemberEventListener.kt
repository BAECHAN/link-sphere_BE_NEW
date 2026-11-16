package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.member.MemberPurgedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 회원이 영구 삭제되면 그 회원의 북마크·폴더·글 좋아요를 지운다(member의 MemberLifecycleEvents.kt).
 * 발행한 쪽의 트랜잭션 안에서 실행돼야 해서 트랜잭션이 없으면 실패한다(MANDATORY).
 */
@Component
class InteractionMemberEventListener(
    private val bookmarkRepository: BookmarkRepository,
    private val bookmarkFolderRepository: BookmarkFolderRepository,
    private val bookmarkFolderItemRepository: BookmarkFolderItemRepository,
    private val postReactionRepository: PostReactionRepository,
) {

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onPurged(event: MemberPurgedEvent) {
        // 폴더 아이템 → 폴더 → 북마크 순으로 지운다(BookmarkFolderService.deleteFolder와 같은 순서 -
        // 폴더가 아이템보다 먼저 사라지면 안 됨). 순서가 중요해 리스너를 나누지 않고 여기 한 곳에 둔다.
        bookmarkFolderItemRepository.deleteByUserId(event.memberId)
        bookmarkFolderRepository.deleteByUserId(event.memberId)
        bookmarkRepository.deleteByUserId(event.memberId)
        postReactionRepository.deleteByUserId(event.memberId)
    }
}
