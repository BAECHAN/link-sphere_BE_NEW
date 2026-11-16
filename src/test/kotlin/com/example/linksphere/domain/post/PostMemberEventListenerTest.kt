package com.example.linksphere.domain.post

import com.example.linksphere.domain.member.MemberPurgedEvent
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.util.UUID

// 트랜잭션 표시(MANDATORY)는 프록시가 있어야 동작하므로 여기서는 보지 않는다 - 실제로 지워지는지는
// AccountPurgeResidueIntegrationTest가 테이블에 남은 행으로 본다.
class PostMemberEventListenerTest {

    @Test
    fun `영구 삭제 때 조회 기록을 지운다`() {
        val memberId = UUID.randomUUID()
        val postViewRepository = mock(PostViewRepository::class.java)

        PostMemberEventListener(postViewRepository).onPurged(MemberPurgedEvent(memberId))

        verify(postViewRepository).deleteByUserId(memberId)
    }
}
