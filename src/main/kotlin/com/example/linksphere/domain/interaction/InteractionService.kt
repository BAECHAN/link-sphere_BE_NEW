package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.PostNotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class InteractionService(
    private val reactionRepository: ReactionRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
    private val bookmarkFolderRepository: BookmarkFolderRepository,
    private val bookmarkFolderItemRepository: BookmarkFolderItemRepository,
) {
    @Transactional
    fun toggleLike(targetId: UUID, targetType: TargetType, userId: UUID): Boolean {
        when (targetType) {
            TargetType.POST -> assertPostVisible(targetId, userId)
            TargetType.COMMENT -> {
                val comment =
                    commentRepository.findByIdOrNull(targetId)
                        ?: throw IllegalArgumentException("Comment not found: $targetId")
                // 비공개 글에 달린 댓글도 글 소유자 외에는 좋아요를 달 수 없어야 한다.
                assertPostVisible(comment.postId, userId)
            }
        }

        val exists = reactionRepository.existsByTargetIdAndTargetTypeAndUserId(targetId, targetType, userId)
        return if (exists) {
            reactionRepository.deleteByTargetIdAndTargetTypeAndUserId(targetId, targetType, userId)
            false
        } else {
            reactionRepository.save(
                TableReaction(
                    userId = userId,
                    targetId = targetId,
                    targetType = targetType,
                    reactionType = ReactionType.LIKE,
                ),
            )
            true
        }
    }

    @Transactional
    fun toggleBookmark(postId: UUID, userId: UUID): Boolean {
        assertPostVisible(postId, userId)

        val exists = bookmarkRepository.existsByUserIdAndPostId(userId, postId)
        return if (exists) {
            // 소속 전부 삭제 — FK ON DELETE CASCADE 로도 처리되지만, 이 레포는 통합 테스트 하네스가
            // 없어 캐스케이드를 단위 테스트로 증명할 수 없다. 명시적으로 먼저 지워 테스트 가능하게 하고,
            // 캐스케이드는 백스톱으로 남겨둔다.
            bookmarkFolderItemRepository.deleteByUserIdAndPostId(userId, postId)
            bookmarkRepository.deleteByUserIdAndPostId(userId, postId)
            false
        } else {
            bookmarkRepository.save(TableBookmark(userId, postId))
            true
        }
    }

    /**
     * 폴더에 추가 — 북마크가 없으면 미분류로 자동 생성 후 소속을 추가한다.
     * "북마크 보장 + 소속 보장" 의미. 이미 그 폴더에 있어도 멱등하게 200.
     */
    @Transactional
    fun addBookmarkFolder(postId: UUID, folderId: UUID, userId: UUID): BookmarkFoldersResponse {
        assertPostVisible(postId, userId)
        val folder = bookmarkFolderRepository.findByIdOrNull(folderId)
            ?: throw BookmarkFolderNotFoundException(folderId)
        if (folder.userId != userId) throw ForbiddenException("Cannot add bookmark to another user's folder")

        bookmarkRepository.insertIgnoreConflict(userId, postId)
        bookmarkFolderItemRepository.insertIgnoreConflict(userId, postId, folderId)

        return BookmarkFoldersResponse(
            postId = postId,
            isBookmarked = true,
            folderIds = bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(userId, postId),
        )
    }

    /**
     * 그 폴더에서만 제거 — 북마크 자체는 유지 (마지막 폴더였어도 미분류로 생존).
     * 없는 소속을 제거해도 멱등 200 — 다른 기기의 stale 상태에서도 안전하게 탭할 수 있어야 하므로
     * 폴더 존재/소유 여부를 별도로 검증하지 않는다 (userId 로 스코프된 삭제라 교차 사용자 노출 없음).
     */
    @Transactional
    fun removeBookmarkFolder(postId: UUID, folderId: UUID, userId: UUID): BookmarkFoldersResponse {
        bookmarkFolderItemRepository.deleteByUserIdAndPostIdAndFolderId(userId, postId, folderId)
        return BookmarkFoldersResponse(
            postId = postId,
            isBookmarked = bookmarkRepository.existsByUserIdAndPostId(userId, postId),
            folderIds = bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(userId, postId),
        )
    }

    /**
     * 폴더 소속 전부 해제 → 미분류로. 북마크 자체는 건드리지 않는다.
     */
    @Transactional
    fun clearBookmarkFolders(postId: UUID, userId: UUID): BookmarkFoldersResponse {
        bookmarkFolderItemRepository.deleteByUserIdAndPostId(userId, postId)
        return BookmarkFoldersResponse(
            postId = postId,
            isBookmarked = bookmarkRepository.existsByUserIdAndPostId(userId, postId),
            folderIds = emptyList(),
        )
    }

    /**
     * 글이 존재하고, 비공개라면 소유자만 접근 가능함을 확인한다.
     * PostService.getPostById와 동일한 기준: 존재 여부를 알려주지 않도록 403이 아닌 404로 던진다.
     */
    private fun assertPostVisible(postId: UUID, userId: UUID) {
        val post = postRepository.findByIdOrNull(postId) ?: throw PostNotFoundException(postId)
        if (post.isPrivate && post.userId != userId) throw PostNotFoundException(postId)
    }
}
