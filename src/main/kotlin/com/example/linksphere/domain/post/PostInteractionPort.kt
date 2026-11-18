package com.example.linksphere.domain.post

import java.util.UUID

/**
 * 글 응답에 싣는 좋아요·북마크 정보와 글 등록 때의 북마크 저장을 묻는 약속(docs/plans/2026-11-16-be-structure-stage2.md PR-9a).
 * 무엇이 필요한지는 post가 정하고 구현은 좋아요·북마크를 가진 interaction이 한다(PostInteractionAdapter) - post는 interaction의
 * 저장소와 엔티티를 모른다(PostCommentPort와 같은 방식, docs/BE-ARCHITECTURE.md §7-8).
 */
interface PostInteractionPort {

    /**
     * 글마다 좋아요·북마크 수와 viewerId의 상태를 한 번에 읽는다. 넘긴 글 id마다 값이 있다(좋아요·북마크가 없으면 0·false).
     * viewerId가 null이면(비로그인) 내 상태는 묻지 않는다.
     */
    fun findSummaries(postIds: Collection<UUID>, viewerId: UUID?): Map<UUID, PostInteractionSummary>

    /** 글 하나의 좋아요·북마크 수와 viewerId의 상태. */
    fun findSummary(postId: UUID, viewerId: UUID?): PostInteractionSummary

    /** userId가 북마크한 글의 id 전부. 글 목록의 `filter=isBookmarked`가 이 집합으로 거른다(PR-9b). */
    fun findBookmarkedPostIds(userId: UUID): Set<UUID>

    /**
     * userId가 최근에 좋아요한 글의 id를 LIKED_FILTER_LIMIT개까지. 글 목록의 `filter=isLiked`가 이 집합으로 거른다(PR-9b).
     * 좋아요는 수에 한도가 없어 전부 넘기면 IN 목록이 드라이버의 바인드 한계(65,535)에 닿는다. 그래서 최근 것만 넘기고, 더 있어서
     * 잘랐으면 truncated로 알린다(docs/plans/2026-11-17-bookmark-limit-liked-filter.md).
     */
    fun findRecentLikedPostIds(userId: UUID): LikedPostIds

    /**
     * 글을 등록하면서 북마크하고 폴더에 넣는다. 없는 폴더는 BookmarkFolderNotFoundException, 남의 폴더는 ForbiddenException이다.
     * 글 행이 DB에 있어야 하므로(북마크 테이블의 FK) 부르는 쪽이 먼저 flush하고, 부르는 쪽의 트랜잭션 안에서 실행한다.
     */
    fun bookmarkNewPost(userId: UUID, postId: UUID, folderIds: List<UUID>)

    companion object {
        // filter=isLiked가 보는 최근 좋아요 수. 북마크 저장 한도(BookmarkLimits.MAX_BOOKMARKS_PER_MEMBER)와 같은 값이다
        const val LIKED_FILTER_LIMIT = 10_000
    }
}

/** 최근에 좋아요한 글 id(ids)와, 좋아요가 더 있어서 잘랐는지(truncated). */
data class LikedPostIds(val ids: Set<UUID>, val truncated: Boolean)

data class PostInteractionSummary(
    val likeCount: Int,
    val isLiked: Boolean,
    val bookmarkCount: Int,
    val isBookmarked: Boolean,
    val bookmarkFolderIds: List<UUID>,
)
