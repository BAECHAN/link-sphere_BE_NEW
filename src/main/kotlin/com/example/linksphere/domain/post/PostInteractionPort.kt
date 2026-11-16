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

    /**
     * 글을 등록하면서 북마크하고 폴더에 넣는다. 없는 폴더는 BookmarkFolderNotFoundException, 남의 폴더는 ForbiddenException이다.
     * 글 행이 DB에 있어야 하므로(북마크 테이블의 FK) 부르는 쪽이 먼저 flush하고, 부르는 쪽의 트랜잭션 안에서 실행한다.
     */
    fun bookmarkNewPost(userId: UUID, postId: UUID, folderIds: List<UUID>)
}

data class PostInteractionSummary(
    val likeCount: Int,
    val isLiked: Boolean,
    val bookmarkCount: Int,
    val isBookmarked: Boolean,
    val bookmarkFolderIds: List<UUID>,
)
