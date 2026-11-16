package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.support.PostgresIntegrationTest
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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

/**
 * 탈퇴가 그 회원의 데이터를 빠짐없이 지우는지를 테이블에 남은 행 수로 고정한다
 * (docs/plans/2026-11-16-be-structure-stage2.md PR-1).
 *
 * 지금은 AccountDeletionService가 각 테이블을 직접 지운다. 2단계 PR-6에서 "회원이 지워졌다"를 알리고 기능마다 자기 데이터를
 * 지우는 방식으로 바뀌는데, 듣는 쪽을 하나 빠뜨려도 예외 없이 끝난다. 그때 이 테스트가 남은 행을 잡는다 - 어느 코드가
 * 지우는지는 보지 않고 결과만 본다. 남의 데이터를 건드리지 않는지도 함께 본다.
 */
@PostgresIntegrationTest
@Import(
    AccountDeletionService::class,
    MemberSessionService::class,
    AccountPurgeResidueIntegrationTest.PasswordEncoderConfig::class,
)
class AccountPurgeResidueIntegrationTest {

    // @DataJpaTest는 SecurityConfig를 띄우지 않는다 - 운영과 같은 BCrypt 구현만 따로 둔다(AccountResignupIntegrationTest와 같다)
    @TestConfiguration(proxyBeanMethods = false)
    class PasswordEncoderConfig {
        @Bean
        fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
    }

    @Autowired private lateinit var accountDeletionService: AccountDeletionService

    @Autowired private lateinit var memberRepository: MemberRepository

    @Autowired private lateinit var passwordEncoder: PasswordEncoder

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var jdbc: JdbcTemplate

    private lateinit var leaver: UUID
    private lateinit var stayer: UUID

    @BeforeEach
    fun seed() {
        leaver = saveMember("leaver@example.com", "leaver")
        stayer = saveMember("stayer@example.com", "stayer")
        val post = UUID.randomUUID()
        val comment = UUID.randomUUID()
        jdbc.update("INSERT INTO posts (id, user_id, url, title, is_private) VALUES (?, ?, 'https://example.com', 't', false)", post, stayer)
        jdbc.update("INSERT INTO comments (id, post_id, user_id, content) VALUES (?, ?, ?, 'c')", comment, post, stayer)

        // 두 회원에게 같은 종류의 데이터를 넣는다 - 떠나는 회원 것만 지워져야 한다
        listOf(leaver, stayer).forEach { member ->
            val folder = UUID.randomUUID()
            jdbc.update("INSERT INTO bookmarks (user_id, post_id) VALUES (?, ?)", member, post)
            jdbc.update("INSERT INTO bookmark_folders (id, user_id, name) VALUES (?, ?, 'f')", folder, member)
            jdbc.update("INSERT INTO bookmark_folder_items (user_id, post_id, folder_id) VALUES (?, ?, ?)", member, post, folder)
            jdbc.update("INSERT INTO post_reactions (user_id, post_id) VALUES (?, ?)", member, post)
            jdbc.update("INSERT INTO comment_reactions (user_id, comment_id) VALUES (?, ?)", member, comment)
            jdbc.update("INSERT INTO post_views (user_id, post_id) VALUES (?, ?)", member, post)
            jdbc.update("INSERT INTO fcm_tokens (user_id, token) VALUES (?, ?)", member, "token-$member")
            jdbc.update(
                "INSERT INTO member_sessions (id, member_id, access_token_hash, refresh_token_hash, access_expires_at, refresh_expires_at, family_id) " +
                    "VALUES (?, ?, ?, ?, now() + interval '1 hour', now() + interval '1 day', ?)",
                UUID.randomUUID(),
                member,
                "a".repeat(32) + member.toString().replace("-", ""),
                "r".repeat(32) + member.toString().replace("-", ""),
                UUID.randomUUID(),
            )
        }
    }

    @Test
    fun `탈퇴를 신청하면 세션이 모두 끊기고 알림 토큰이 지워진다`() {
        accountDeletionService.requestDeletion(leaver, RAW_PASSWORD)
        entityManager.flush()

        assertEquals(0, liveSessions(leaver))
        assertEquals(0, rows("fcm_tokens", leaver))
        // 유예 중에는 복구할 수 있어야 하므로 나머지는 그대로 둔다
        PURGED_TABLES.filter { it != "fcm_tokens" }.forEach { assertEquals(1, rows(it, leaver), it) }
        assertEquals(1, liveSessions(stayer))
        assertEquals(1, rows("fcm_tokens", stayer))
    }

    @Test
    fun `영구 삭제 뒤에는 그 회원의 행이 어느 테이블에도 남지 않고 남의 행은 그대로다`() {
        accountDeletionService.requestDeletion(leaver, RAW_PASSWORD)

        assertTrue(accountDeletionService.purge(leaver, Instant.now().plusSeconds(1)))
        entityManager.flush()

        PURGED_TABLES.forEach { assertEquals(0, rows(it, leaver), it) }
        assertEquals(0, liveSessions(leaver))
        PURGED_TABLES.forEach { assertEquals(1, rows(it, stayer), it) }
        assertEquals(1, liveSessions(stayer))
    }

    private fun rows(table: String, member: UUID): Int = jdbc.queryForObject("SELECT count(*) FROM $table WHERE user_id = ?", Int::class.java, member)!!

    private fun liveSessions(member: UUID): Int = jdbc.queryForObject("SELECT count(*) FROM member_sessions WHERE member_id = ? AND revoked_at IS NULL", Int::class.java, member)!!

    private fun saveMember(email: String, nickname: String): UUID = memberRepository.saveAndFlush(TableMember(email = email, password = passwordEncoder.encode(RAW_PASSWORD), nickname = nickname)).id!!

    companion object {
        private const val RAW_PASSWORD = "password1!"

        // 영구 삭제가 비워야 하는 테이블. 회원 데이터를 담는 테이블이 새로 생기면 여기에 더한다
        private val PURGED_TABLES =
            listOf("bookmark_folder_items", "bookmark_folders", "bookmarks", "post_reactions", "comment_reactions", "post_views", "fcm_tokens")
    }
}
