package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.member.SignupRequest
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.global.exception.DuplicateMemberException
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.util.UUID

// 탈퇴한 이메일로 다시 가입하는 정책(docs/ACCOUNT-DELETION.md §5-3)을 실제 Postgres로 고정한다. 유예 중에는 원래 이메일이
// 남아 있어 가입이 막히고(로그인하면 복구된다), 퍼지 뒤에는 새 회원 행으로 가입되며 옛 글은 옛 회원 행을 계속 가리킨다.
// 이메일 유니크 인덱스(members_email_lower_key)와 퍼지의 익명화가 함께 만드는 동작이라 mock 단위 테스트로는 볼 수 없다.
@PostgresIntegrationTest
@Import(
    MemberService::class,
    AccountDeletionService::class,
    MemberSessionService::class,
    AccountResignupIntegrationTest.PasswordEncoderConfig::class,
)
class AccountResignupIntegrationTest {

    // @DataJpaTest는 SecurityConfig를 띄우지 않는다 - 운영과 같은 BCrypt 구현(SecurityConfig.passwordEncoder)만 따로 둔다
    @TestConfiguration(proxyBeanMethods = false)
    class PasswordEncoderConfig {
        @Bean
        fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
    }

    @Autowired private lateinit var memberService: MemberService

    @Autowired private lateinit var accountDeletionService: AccountDeletionService

    @Autowired private lateinit var memberRepository: MemberRepository

    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `유예 중에는 같은 이메일로 가입할 수 없다`() {
        val leaver = saveMember(EMAIL, "leaver")
        accountDeletionService.requestDeletion(leaver.id!!, RAW_PASSWORD)

        assertFalse(memberService.isEmailAvailable(EMAIL))
        assertThrows(DuplicateMemberException::class.java) {
            memberService.signup(SignupRequest(EMAIL, "enc", "returner"))
        }
    }

    @Test
    fun `퍼지 뒤에는 같은 이메일이 새 회원이 되고 옛 글은 옛 회원을 가리킨다`() {
        val oldId = saveMember(EMAIL, "leaver").id!!
        val postId = UUID.randomUUID()
        jdbc.update("INSERT INTO posts (id, user_id, url, title, is_private) VALUES (?, ?, 'https://example.com', 't', false)", postId, oldId)
        accountDeletionService.requestDeletion(oldId, RAW_PASSWORD)

        assertTrue(accountDeletionService.purge(oldId, Instant.now().plusSeconds(1)))
        assertTrue(memberService.isEmailAvailable(EMAIL))

        val renewed = memberService.signup(SignupRequest(EMAIL, "enc", "returner"))

        assertNotEquals(oldId, renewed.id)
        // JdbcTemplate 조회는 Hibernate 자동 flush를 타지 않는다 - 퍼지의 익명화를 먼저 DB로 내보낸다
        memberRepository.flush()
        assertEquals("deleted-$oldId@deleted.invalid", jdbc.queryForObject("SELECT email FROM members WHERE id = ?", String::class.java, oldId))
        assertEquals(oldId, jdbc.queryForObject("SELECT user_id FROM posts WHERE id = ?", UUID::class.java, postId))
    }

    private fun saveMember(email: String, nickname: String): TableMember = memberRepository.saveAndFlush(TableMember(email = email, password = passwordEncoder.encode(RAW_PASSWORD), nickname = nickname))

    companion object {
        private const val EMAIL = "leaver@example.com"
        private const val RAW_PASSWORD = "password1!"
    }
}
