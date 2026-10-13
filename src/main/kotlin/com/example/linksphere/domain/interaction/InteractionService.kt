package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.post.PostLikeResponse
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.InvalidInputException
import com.example.linksphere.global.exception.PostNotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class InteractionService(
    private val postReactionRepository: PostReactionRepository,
    private val commentReactionRepository: CommentReactionRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
    private val bookmarkFolderRepository: BookmarkFolderRepository,
    private val bookmarkFolderItemRepository: BookmarkFolderItemRepository,
) {
    /**
     * 토글은 "먼저 지워보고, 지운 행이 없으면 추가"로 처리한다 — exists 조회 후 분기하면 같은 사용자의
     * 동시 요청이 둘 다 같은 분기로 들어가 두 번째 삭제가 StaleObjectStateException(500)을 냈다
     * (2026-10-13 운영). 일괄 DELETE는 이미 지워진 행에 0을 반환하고(READ COMMITTED에서 먼저 지운
     * 쪽 커밋을 기다렸다가 행을 건너뛴다), insert는 ON CONFLICT DO NOTHING이라 어떤 순서로 겹쳐도
     * 예외가 나지 않는다. 근거: docs/plans/2026-10-13-like-concurrency.md
     */
    @Transactional
    fun togglePostLike(postId: UUID, userId: UUID): PostLikeResponse {
        assertPostVisible(postId, userId)

        val removed = postReactionRepository.deleteByUserIdAndPostId(userId, postId) > 0

        if (!removed) {
            postReactionRepository.insertIgnoreConflict(userId, postId)
        }

        val isLiked = !removed

        // 동일 트랜잭션 내 COUNT — insert/delete가 반영된 정확값
        val likeCount = postReactionRepository.countByPostId(postId).toInt()
        return PostLikeResponse(isLiked = isLiked, likeCount = likeCount)
    }

    /**
     * 좋아요 상태를 지정한다(liked=true면 추가, false면 취소) — 토글과 달리 같은 요청을 여러 번
     * 보내거나 동시에 보내도 결과가 같다(멱등). 이미 그 상태여도 200으로 현재 상태를 돌려준다.
     * 비공개 글 확인은 취소에도 유지한다 — 응답의 likeCount가 남의 비공개 글 정보를 흘리지 않게.
     */
    @Transactional
    fun setPostLike(postId: UUID, userId: UUID, liked: Boolean): PostLikeResponse {
        assertPostVisible(postId, userId)

        if (liked) {
            postReactionRepository.insertIgnoreConflict(userId, postId)
        } else {
            postReactionRepository.deleteByUserIdAndPostId(userId, postId)
        }

        // 동일 트랜잭션 내 COUNT — insert/delete가 반영된 정확값
        val likeCount = postReactionRepository.countByPostId(postId).toInt()
        return PostLikeResponse(isLiked = liked, likeCount = likeCount)
    }

    @Transactional
    fun toggleCommentLike(commentId: UUID, userId: UUID): Boolean {
        assertCommentLikeable(commentId, userId)

        // togglePostLike 와 같은 "먼저 지워보고 없으면 추가" 방식(동시 요청에도 예외 없음)
        val removed = commentReactionRepository.deleteByUserIdAndCommentId(userId, commentId) > 0

        if (!removed) {
            commentReactionRepository.insertIgnoreConflict(userId, commentId)
        }

        return !removed
    }

    /** setPostLike 의 댓글판 — 댓글 확인 기준은 toggleCommentLike 와 같다. */
    @Transactional
    fun setCommentLike(commentId: UUID, userId: UUID, liked: Boolean): Boolean {
        assertCommentLikeable(commentId, userId)

        if (liked) {
            commentReactionRepository.insertIgnoreConflict(userId, commentId)
        } else {
            commentReactionRepository.deleteByUserIdAndCommentId(userId, commentId)
        }

        return liked
    }

    @Transactional
    fun toggleBookmark(postId: UUID, userId: UUID): Boolean {
        assertPostVisible(postId, userId)

        // togglePostLike 와 같은 "먼저 지워보고 없으면 추가" 방식(동시 요청에도 예외 없음).
        // 소속 전부 삭제 — FK ON DELETE CASCADE 로도 처리되지만, 이 레포는 통합 테스트 하네스가
        // 없어 캐스케이드를 단위 테스트로 증명할 수 없다. 명시적으로 먼저 지워 테스트 가능하게 하고,
        // 캐스케이드는 백스톱으로 남겨둔다. 북마크가 없으면 소속도 없으므로 이 삭제는 0행으로 끝난다.
        bookmarkFolderItemRepository.deleteByUserIdAndPostId(userId, postId)
        val removed = bookmarkRepository.deleteByUserIdAndPostId(userId, postId) > 0

        if (!removed) {
            bookmarkRepository.insertIgnoreConflict(userId, postId)
        }

        return !removed
    }

    /**
     * 북마크 상태를 지정한다 — 토글과 달리 같은 요청을 여러 번·동시에 보내도 결과가 같다(멱등).
     * 저장(true)은 북마크만 보장하고 이미 있는 폴더 소속은 건드리지 않는다(addBookmarkFolder와 같은 방식 —
     * 낡은 화면이 다른 기기에서 정리한 분류를 지우지 않게). 그래서 응답에 서버의 실제 folderIds를 담는다.
     * 해제(false)는 removeBookmarkFolder·clearBookmarkFolders처럼 공개 여부를 확인하지 않는다 — 응답에 수가
     * 없어 새는 정보가 없고, 비공개로 바뀐 글의 북마크도 지울 수 있어야 한다.
     * 근거: docs/plans/2026-10-13-bookmark-set-state.md
     */
    @Transactional
    fun setBookmark(postId: UUID, userId: UUID, bookmarked: Boolean): BookmarkFoldersResponse {
        if (!bookmarked) {
            bookmarkFolderItemRepository.deleteByUserIdAndPostId(userId, postId)
            bookmarkRepository.deleteByUserIdAndPostId(userId, postId)
            return BookmarkFoldersResponse(postId = postId, isBookmarked = false, folderIds = emptyList())
        }

        assertPostVisible(postId, userId)
        bookmarkRepository.insertIgnoreConflict(userId, postId)

        return BookmarkFoldersResponse(
            postId = postId,
            isBookmarked = true,
            folderIds = bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(userId, postId),
        )
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

    private fun assertCommentLikeable(commentId: UUID, userId: UUID) {
        val comment =
            commentRepository.findByIdOrNull(commentId)
                ?: throw IllegalArgumentException("Comment not found: $commentId")
        // 비공개 글에 달린 댓글도 글 소유자 외에는 좋아요를 달 수 없어야 한다.
        assertPostVisible(comment.postId, userId)
        // 비공개 글 검증 뒤에 확인해야 한다 — 순서를 바꾸면 남의 비공개 글의 삭제된 댓글에
        // 404 대신 400이 나가면서 "그 댓글이 존재한다"는 사실이 새어나간다.
        if (comment.isDeleted) throw InvalidInputException("삭제된 댓글에는 좋아요를 누를 수 없습니다.")
    }
}
