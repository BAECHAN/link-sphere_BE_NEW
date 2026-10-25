package com.example.linksphere.domain.member

import com.example.linksphere.domain.auth.SignupRequest
import com.example.linksphere.global.exception.DuplicateMemberException
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException

// 가입의 사전 중복 확인과 DB 유니크 인덱스 사이 레이스를 실제 Postgres로 본다. MemberServiceTest는 save()가 곧바로
// 제약 위반을 던지도록 mock해서, @GeneratedValue UUID 엔티티의 INSERT가 커밋까지 미뤄진다는 사실을 보지 못했다.
//
// 사전 확인(existsByEmail)은 정확 일치이고 인덱스(members_email_lower_key)는 lower(email)이라, 대문자 이메일 행을 먼저
// 넣어 두면 동시 가입 요청이 둘 다 사전 확인을 통과한 상황을 그대로 재현할 수 있다.
@PostgresIntegrationTest
@Import(MemberService::class)
class MemberServiceIntegrationTest {

    @Autowired private lateinit var memberService: MemberService

    @Autowired private lateinit var memberRepository: MemberRepository

    @Test
    fun `save는 INSERT를 미뤄 제약 위반이 flush에서야 난다`() {
        memberRepository.saveAndFlush(TableMember(email = "Race@Example.com", password = "enc", nickname = "first"))

        // 예외 없이 끝난다 - persist만 하고 INSERT는 flush(보통 커밋) 때 나간다
        memberRepository.save(TableMember(email = "race@example.com", password = "enc", nickname = "second"))

        val exception = assertThrows(DataIntegrityViolationException::class.java) { memberRepository.flush() }
        assertTrue(exception.mostSpecificCause.message!!.contains("members_email_lower_key"))
    }

    @Test
    fun `사전 확인을 통과한 이메일 중복도 가입 안에서 DuplicateMemberException으로 바뀐다`() {
        memberRepository.saveAndFlush(TableMember(email = "Race@Example.com", password = "enc", nickname = "first"))

        assertThrows(DuplicateMemberException::class.java) {
            memberService.signup(SignupRequest("race@example.com", "enc", "second"))
        }
    }
}
