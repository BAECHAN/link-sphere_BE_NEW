package com.example.linksphere.domain.feed

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface FeedItemRepository : JpaRepository<TableFeedItem, UUID> {
    fun findAllByNormalizedUrlIn(normalizedUrls: Collection<String>): List<TableFeedItem>

    // 이미 처리된 URL이면 0을 반환한다 - BookmarkRepository.insertIgnoreConflict와 동일한 형태.
    // post_id는 아직 게시글이 없는 시점이라 우선 NULL로 claim하고, 생성에 성공하면 attachPost로 채운다.
    @Modifying
    @Query(
        value = "INSERT INTO feed_items (id, source_id, post_id, normalized_url, created_at) " +
            "VALUES (:id, :sourceId, :postId, :normalizedUrl, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
        nativeQuery = true,
    )
    fun claim(
        @Param("id") id: UUID,
        @Param("sourceId") sourceId: UUID?,
        @Param("postId") postId: UUID?,
        @Param("normalizedUrl") normalizedUrl: String,
    ): Int

    // @Modifying 쿼리는 JDBC로 직접 나가 영속성 컨텍스트의 dirty-checking 플러시 순서를 타지 않는다.
    // FeedItemProcessor.processFeedItem에서 postService.createPost(...)가 만든 TablePost의 INSERT는
    // 아직 flush되지 않은 상태라, flushAutomatically 없이 이 UPDATE를 그대로 내보내면 방금 만든
    // post_id가 DB에 없어 fk_feed_items_post 위반으로 실패한다(실제로 재현 확인됨) - 반드시 필요.
    @Modifying(flushAutomatically = true)
    @Query("UPDATE TableFeedItem f SET f.postId = :postId WHERE f.id = :id")
    fun attachPost(@Param("id") id: UUID, @Param("postId") postId: UUID)
}
