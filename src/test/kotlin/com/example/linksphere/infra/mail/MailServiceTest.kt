package com.example.linksphere.infra.mail

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

// fromAddress가 비어있는 경로만 검증한다 - 그 경우 sesClient(lazy)에 아예 접근하지 않아
// 실제 AWS 자격증명 없이도 CI에서 안전하게 돌릴 수 있다. 실제 발송 성공 여부는 이 레포에
// 실 AWS 대상 테스트 인프라가 없어(다른 서비스도 동일, CommentServiceTest 참고) 배포 후
// 수동 확인이 필요하다.
class MailServiceTest {

    @Test
    fun `발신 주소가 비어있으면 예외 없이 조용히 발송을 건너뛴다`() {
        val service = MailService(fromAddress = "")

        assertDoesNotThrow {
            service.send("to@example.com", "제목", "<p>본문</p>")
        }
    }
}
