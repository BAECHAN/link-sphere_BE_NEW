package com.example.linksphere.domain.member

import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
}
