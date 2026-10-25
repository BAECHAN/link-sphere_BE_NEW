package com.example.linksphere.domain.interaction

import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID

// 같은 회원의 폴더 이름 유니크 제약(uk_bookmark_folders_user_name) 위반이 어디서 나는지 실제 Postgres로 본다. 폴더는 id를
// 앱에서 만들어 save()가 merge로 가고, 이름 변경은 dirty checking이라, 둘 다 위반이 save·대입 시점이 아니라 flush(보통
// 커밋)에서 난다 - BookmarkFolderService가 사전 확인을 함께 통과한 동시 요청을 DUPLICATE_FOLDER_NAME으로 바꾸려면 그
// 안에서 flush해야 한다는 근거다(MemberServiceIntegrationTest와 같은 문제).
@PostgresIntegrationTest
class BookmarkFolderRepositoryIntegrationTest {

    @Autowired private lateinit var bookmarkFolderRepository: BookmarkFolderRepository

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
}
