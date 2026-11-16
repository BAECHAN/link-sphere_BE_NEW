package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.member.MemberPurgedEvent
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.util.UUID

// 트랜잭션 표시(MANDATORY)는 프록시가 있어야 동작하므로 여기서는 보지 않는다 - 실제로 지워지는지는
// AccountPurgeResidueIntegrationTest가 테이블에 남은 행으로 본다.
class InteractionMemberEventListenerTest {

    // 폴더가 아이템보다 먼저 사라지면 안 된다
    @Test
    fun `영구 삭제 때 폴더 아이템, 폴더, 북마크 순으로 지우고 글 좋아요를 지운다`() {
        val memberId = UUID.randomUUID()
        val bookmarkRepository = mock(BookmarkRepository::class.java)
        val bookmarkFolderRepository = mock(BookmarkFolderRepository::class.java)
        val bookmarkFolderItemRepository = mock(BookmarkFolderItemRepository::class.java)
        val postReactionRepository = mock(PostReactionRepository::class.java)
        val listener = InteractionMemberEventListener(bookmarkRepository, bookmarkFolderRepository, bookmarkFolderItemRepository, postReactionRepository)

        listener.onPurged(MemberPurgedEvent(memberId))

        val order = inOrder(bookmarkFolderItemRepository, bookmarkFolderRepository, bookmarkRepository)
        order.verify(bookmarkFolderItemRepository).deleteByUserId(memberId)
        order.verify(bookmarkFolderRepository).deleteByUserId(memberId)
        order.verify(bookmarkRepository).deleteByUserId(memberId)
        verify(postReactionRepository).deleteByUserId(memberId)
    }
}
