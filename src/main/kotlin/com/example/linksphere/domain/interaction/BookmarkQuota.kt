package com.example.linksphere.domain.interaction

import com.example.linksphere.global.exception.BookmarkLimitExceededException
import java.util.UUID

/**
 * 북마크 저장 한도(BookmarkLimits.MAX_BOOKMARKS_PER_MEMBER) 검사. 북마크를 새로 만드는 곳은 insert 전에 부른다 -
 * InteractionService.setBookmark·addBookmarkFolder, BookmarkFolderService.batchAddBookmarksToFolder,
 * PostInteractionAdapter.bookmarkNewPost.
 * 이미 북마크한 글은 한도와 상관없이 통과한다(저장 요청은 멱등이다). 트랜잭션은 호출부의 것을 쓴다.
 * 빈이 아니라 저장소에 붙인 함수다 - 저장소 하나만 쓰고 상태가 없다.
 */
internal fun BookmarkRepository.assertCanAdd(userId: UUID, postId: UUID) {
    assertCanAddAll(userId, listOf(postId))
}

/** postIds 가운데 아직 북마크하지 않은 글을 모두 더했을 때 한도를 넘으면 BookmarkLimitExceededException. */
internal fun BookmarkRepository.assertCanAddAll(userId: UUID, postIds: Collection<UUID>) {
    val distinctIds = postIds.distinct()

    if (distinctIds.isEmpty()) {
        return
    }

    val newCount = distinctIds.size - findAllByUserIdAndPostIdIn(userId, distinctIds).size

    if (newCount == 0) {
        return
    }

    if (countByUserId(userId) + newCount > BookmarkLimits.MAX_BOOKMARKS_PER_MEMBER) {
        throw BookmarkLimitExceededException(BookmarkLimits.MAX_BOOKMARKS_PER_MEMBER)
    }
}
