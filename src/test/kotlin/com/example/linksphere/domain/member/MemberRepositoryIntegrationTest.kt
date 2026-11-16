package com.example.linksphere.domain.member

import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.LocalDateTime

// findFirstByIsBotTrue는 @Query라 메서드 이름의 First가 무시되고 단건으로 실행된다. 봇이 두 행이면 LIMIT 없는 쿼리는
// IncorrectResultSizeDataAccessException을 던져 피드 크롤링 전체가 멈춘다 - 실제 Postgres로 두 행을 넣어 본다.
@PostgresIntegrationTest
class MemberRepositoryIntegrationTest {

    @Autowired private lateinit var memberRepository: MemberRepository

    @Test
    fun `봇 회원이 두 명이어도 가장 먼저 만든 봇 하나를 돌려준다`() {
        val first =
            memberRepository.saveAndFlush(
                TableMember(email = "bot1@example.com", password = "enc", nickname = "bot1", isBot = true, createdAt = LocalDateTime.of(2026, 1, 1, 0, 0)),
            )
        memberRepository.saveAndFlush(
            TableMember(email = "bot2@example.com", password = "enc", nickname = "bot2", isBot = true, createdAt = LocalDateTime.of(2026, 2, 1, 0, 0)),
        )

        assertEquals(first.id, memberRepository.findFirstByIsBotTrue()?.id)
    }

    // 글 목록의 닉네임 필터가 쓰는 조회 - 예전에는 글 쿼리 안의 서브쿼리였다. 대소문자를 가리지 않고, 탈퇴 신청 중인 회원은
    // 닉네임이 남아 있어도 빠진다. 익명화된 회원은 닉네임이 null이라 걸리지 않는다.
    @Test
    fun `닉네임 패턴 조회는 대소문자를 가리지 않고 탈퇴 신청 중인 회원과 익명화된 회원을 뺀다`() {
        val upper = memberRepository.saveAndFlush(TableMember(email = "u@example.com", password = "enc", nickname = "DevKim"))
        val lower = memberRepository.saveAndFlush(TableMember(email = "l@example.com", password = "enc", nickname = "kimdev"))
        memberRepository.saveAndFlush(TableMember(email = "p@example.com", password = "enc", nickname = "kimpending", deletionRequestedAt = Instant.now()))
        memberRepository.saveAndFlush(TableMember(email = "d@example.com", password = "enc", nickname = null, deletedAt = Instant.now()))
        memberRepository.saveAndFlush(TableMember(email = "o@example.com", password = "enc", nickname = "other"))

        assertEquals(setOf(upper.id, lower.id), memberRepository.findIdsByNicknamePattern("%kim%").toSet())
    }

    // 입력의 `_`를 이스케이프하지 않는 것은 예전 동작 그대로다 - 한 글자 와일드카드로 동작한다
    @Test
    fun `닉네임 패턴의 밑줄은 한 글자 와일드카드로 동작한다`() {
        val member = memberRepository.saveAndFlush(TableMember(email = "w@example.com", password = "enc", nickname = "abc"))

        assertEquals(listOf(member.id), memberRepository.findIdsByNicknamePattern("%a_c%"))
    }

    @Test
    fun `봇 id 조회는 봇 회원만 모두 돌려준다`() {
        val bot1 = memberRepository.saveAndFlush(TableMember(email = "bot1@example.com", password = "enc", nickname = "bot1", isBot = true))
        val bot2 = memberRepository.saveAndFlush(TableMember(email = "bot2@example.com", password = "enc", nickname = "bot2", isBot = true))
        memberRepository.saveAndFlush(TableMember(email = "human@example.com", password = "enc", nickname = "human"))

        assertEquals(setOf(bot1.id, bot2.id), memberRepository.findBotIds().toSet())
    }
}
