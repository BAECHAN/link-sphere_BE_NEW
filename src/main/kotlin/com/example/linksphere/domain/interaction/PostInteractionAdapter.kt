package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.PostInteractionPort
import com.example.linksphere.domain.post.PostInteractionSummary
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.ForbiddenException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * post가 정한 약속(PostInteractionPort)을 interaction이 구현한다 - 글 응답에 싣는 좋아요·북마크 정보를 읽고, 글 등록 때의
 * 북마크를 저장한다. 읽기는 트랜잭션을 스스로 열지 않고 호출부(PostResponseAssembler를 부른 Service)의 것을 쓴다.
 *
 * InteractionService·BookmarkFolderService가 구현하지 않는 이유: 두 Service는 글을 PostLookupService에 묻고
 * BookmarkFolderService는 PostResponseAssembler도 주입받는다. post의 조립기가 이 약속으로 그 Service를 주입받으면 빈이
 * 서로 물려 저장소만 참조하는 구현을 따로 둔다(CommentCountAdapter와 같은 이유).
 */
@Component
class PostInteractionAdapter(
    private val bookmarkRepository: BookmarkRepository,
    private val bookmarkFolderItemRepository: BookmarkFolderItemRepository,
    private val bookmarkFolderRepository: BookmarkFolderRepository,
    private val postReactionRepository: PostReactionRepository,
) : PostInteractionPort {

    // 글 수와 무관하게 쿼리는 비로그인 2번, 로그인 5번이다. 개수는 DB에서 GROUP BY로 센다(docs/BE-ARCHITECTURE.md §7-8)
    override fun findSummaries(postIds: Collection<UUID>, viewerId: UUID?): Map<UUID, PostInteractionSummary> {
        val ids = postIds.toList()

        val bookmarkCounts = bookmarkRepository.countByPostIdIn(ids).associate { it.postId to it.count.toInt() }
        val bookmarkedPostIds = viewerId?.let { viewer -> bookmarkRepository.findAllByUserIdAndPostIdIn(viewer, ids).map { it.postId }.toSet() } ?: emptySet()
        val folderIdsByPost =
            viewerId?.let { viewer -> bookmarkFolderItemRepository.findAllByUserIdAndPostIdIn(viewer, ids).groupBy({ it.postId }, { it.folderId }) } ?: emptyMap()
        val likeCounts = postReactionRepository.countByPostIdIn(ids).associate { it.postId to it.count.toInt() }
        val likedPostIds = viewerId?.let { viewer -> postReactionRepository.findAllByUserIdAndPostIdIn(viewer, ids).map { it.postId }.toSet() } ?: emptySet()

        return ids.associateWith { postId ->
            PostInteractionSummary(
                likeCount = likeCounts[postId] ?: 0,
                isLiked = postId in likedPostIds,
                bookmarkCount = bookmarkCounts[postId] ?: 0,
                isBookmarked = postId in bookmarkedPostIds,
                bookmarkFolderIds = folderIdsByPost[postId] ?: emptyList(),
            )
        }
    }

    override fun findSummary(postId: UUID, viewerId: UUID?): PostInteractionSummary {
        val isBookmarked = viewerId?.let { bookmarkRepository.existsByUserIdAndPostId(it, postId) } ?: false
        // 북마크하지 않은 글은 폴더에 들어 있을 수 없어 묻지 않는다. isBookmarked가 true면 viewerId는 null이 아니다
        val bookmarkFolderIds = if (viewerId != null && isBookmarked) bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(viewerId, postId) else emptyList()

        return PostInteractionSummary(
            likeCount = postReactionRepository.countByPostId(postId).toInt(),
            isLiked = viewerId?.let { postReactionRepository.existsByUserIdAndPostId(it, postId) } ?: false,
            bookmarkCount = bookmarkRepository.countByPostId(postId).toInt(),
            isBookmarked = isBookmarked,
            bookmarkFolderIds = bookmarkFolderIds,
        )
    }

    override fun findBookmarkedPostIds(userId: UUID): Set<UUID> = bookmarkRepository.findPostIdsByUserId(userId).toSet()

    override fun findLikedPostIds(userId: UUID): Set<UUID> = postReactionRepository.findPostIdsByUserId(userId).toSet()

    // InteractionService.addBookmarkFolder와 같은 검증·insert 순서다. insert가 네이티브 쿼리라 트랜잭션 밖에서 불리면 안 된다
    @Transactional(propagation = Propagation.MANDATORY)
    override fun bookmarkNewPost(userId: UUID, postId: UUID, folderIds: List<UUID>) {
        if (folderIds.isNotEmpty()) {
            val folders = bookmarkFolderRepository.findAllById(folderIds)
            val foundIds = folders.map { it.id }.toSet()
            folderIds.firstOrNull { it !in foundIds }?.let { throw BookmarkFolderNotFoundException(it) }
            folders.firstOrNull { it.userId != userId }
                ?.let { throw ForbiddenException("Cannot add bookmark to another user's folder") }
        }

        bookmarkRepository.insertIgnoreConflict(userId, postId)
        folderIds.forEach { folderId ->
            bookmarkFolderItemRepository.insertIgnoreConflict(userId, postId, folderId)
        }
    }
}
