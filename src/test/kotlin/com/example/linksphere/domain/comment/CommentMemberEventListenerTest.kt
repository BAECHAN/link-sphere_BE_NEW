package com.example.linksphere.domain.comment

import com.example.linksphere.domain.member.MemberPurgedEvent
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.util.UUID

// 트랜잭션 표시(MANDATORY)는 프록시가 있어야 동작하므로 여기서는 보지 않는다 - 실제로 지워지는지는
// AccountPurgeResidueIntegrationTest가 테이블에 남은 행으로 본다.
class CommentMemberEventListenerTest {

    @Test
    fun `영구 삭제 때 댓글 좋아요를 지운다`() {
        val memberId = UUID.randomUUID()
        val commentReactionRepository = mock(CommentReactionRepository::class.java)

        CommentMemberEventListener(commentReactionRepository).onPurged(MemberPurgedEvent(memberId))

        verify(commentReactionRepository).deleteByUserId(memberId)
    }
}
