package com.example.linksphere.domain.interaction

import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.UUID

// 같은 회원의 폴더 이름 유니크 제약(uk_bookmark_folders_user_name) 위반이 어디서 나는지 실제 Postgres로 본다. 폴더는 id를
// 앱에서 만들어 save()가 merge로 가고, 이름 변경은 dirty checking이라, 둘 다 위반이 save·대입 시점이 아니라 flush(보통
// 커밋)에서 난다 - BookmarkFolderService가 사전 확인을 함께 통과한 동시 요청을 DUPLICATE_FOLDER_NAME으로 바꾸려면 그
// 안에서 flush해야 한다는 근거다(MemberServiceIntegrationTest와 같은 문제).
@PostgresIntegrationTest
class BookmarkFolderRepositoryIntegrationTest {

    @Autowired private lateinit var bookmarkFolderRepository: BookmarkFolderRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val userId = UUID.randomUUID()

    @Test
    fun `같은 이름 폴더 생성의 제약 위반은 save가 아니라 flush에서 난다`() {
        bookmarkFolderRepository.saveAndFlush(TableBookmarkFolder(userId = userId, name = "읽을거리"))

        bookmarkFolderRepository.save(TableBookmarkFolder(userId = userId, name = "읽을거리"))

        val exception = assertThrows(DataIntegrityViolationException::class.java) { bookmarkFolderRepository.flush() }
        assertTrue(exception.mostSpecificCause.message!!.contains("uk_bookmark_folders_user_name"))
    }

    @Test
    fun `이미 있는 이름으로 바꾸는 제약 위반도 flush에서 난다`() {
        bookmarkFolderRepository.saveAndFlush(TableBookmarkFolder(userId = userId, name = "읽을거리"))
        val other = bookmarkFolderRepository.saveAndFlush(TableBookmarkFolder(userId = userId, name = "나중에", sortOrder = 1))

        other.name = "읽을거리"

        val exception = assertThrows(DataIntegrityViolationException::class.java) { bookmarkFolderRepository.flush() }
        assertTrue(exception.mostSpecificCause.message!!.contains("uk_bookmark_folders_user_name"))
    }

    // DB 컬럼은 varchar(100)이다. 앱이 길이를 보지 않으면 101자 이름은 유니크 제약이 아닌 무결성 위반으로 flush에서 나고,
    // 전역 안전망(GlobalExceptionHandler)이 그것을 409 DUPLICATE_RESOURCE "이미 존재하는 항목입니다."로 바꾼다 -
    // BookmarkFolderService가 길이를 먼저 검사해야 하는 근거다(2026-10-26 재현).
    @Test
    fun `100자를 넘는 폴더 이름은 유니크 제약이 아닌 길이 위반으로 flush에서 난다`() {
        val exception =
            assertThrows(DataIntegrityViolationException::class.java) {
                bookmarkFolderRepository.saveAndFlush(TableBookmarkFolder(userId = userId, name = "가".repeat(101)))
            }

        assertTrue(exception.mostSpecificCause.message!!.contains("value too long"))
    }

    @Test
    fun `pinned_at 은 저장한 뒤 다시 읽어도 남는다`() {
        val pinnedAt = LocalDateTime.of(2026, 10, 26, 12, 0)
        val saved = bookmarkFolderRepository.saveAndFlush(TableBookmarkFolder(userId = userId, name = "읽을거리", pinnedAt = pinnedAt))

        assertEquals(pinnedAt, jdbc.queryForObject("SELECT pinned_at FROM bookmark_folders WHERE id = ?", LocalDateTime::class.java, saved.id))
    }

    // 이름 변경 트랜잭션이 폴더를 읽은 뒤 다른 요청의 고정이 먼저 커밋된다. 기본 UPDATE는 바뀐 컬럼만이 아니라 갱신 가능한 컬럼을
    // 전부 SET에 넣어(Hibernate 6.6.53 UpdateCoordinatorStandard - @DynamicUpdate가 아니면 미리 만든 정적 UPDATE를 쓴다)
    // 이름 변경이 읽어 둔 pinned_at = null로 고정을 덮는다. 반대 순서(고정이 읽어 둔 옛 이름으로 이름 변경을 덮음)도 같은 원리다.
    // TableBookmarkFolder의 @DynamicUpdate가 이 유실을 막는지 본다. 실제로 두 커밋이 겹쳐야 하므로 테스트 트랜잭션 밖에서 돌고
    // 끝나면 지운다(ReactionRepositoryIntegrationTest와 같은 방식).
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `이름 변경이 읽은 뒤 고정이 커밋돼도 이름 변경 커밋이 고정을 덮지 않는다`() {
        val tx = TransactionTemplate(transactionManager)
        val pinTx = TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
        val folderId = tx.execute { bookmarkFolderRepository.save(TableBookmarkFolder(userId = userId, name = "읽을거리")).id }!!

        try {
            tx.executeWithoutResult {
                val renaming = bookmarkFolderRepository.findByIdOrNull(folderId)!!
                pinTx.executeWithoutResult { bookmarkFolderRepository.findByIdOrNull(folderId)!!.pinnedAt = LocalDateTime.now() }
                renaming.name = "나중에"
            }

            val folder = bookmarkFolderRepository.findByIdOrNull(folderId)!!
            assertEquals("나중에", folder.name)
            assertNotNull(folder.pinnedAt)
        } finally {
            jdbc.update("DELETE FROM bookmark_folders WHERE user_id = ?", userId)
        }
    }
}
