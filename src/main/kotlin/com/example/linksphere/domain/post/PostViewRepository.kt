package com.example.linksphere.domain.post

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface PostViewRepository : JpaRepository<TablePostView, PostViewId> {

    // bookmark_folder_items.insertIgnoreConflict(DO NOTHING)와 달리 DO UPDATE — 볼 때마다
    // viewed_at을 갱신해야 "최근 열람순" 정렬이 의미가 있다.
    @Modifying
    @Query(
        value = "INSERT INTO post_views (user_id, post_id, viewed_at) " +
            "VALUES (:userId, :postId, CURRENT_TIMESTAMP) " +
            "ON CONFLICT (user_id, post_id) DO UPDATE SET viewed_at = CURRENT_TIMESTAMP",
        nativeQuery = true,
    )
    fun upsertView(@Param("userId") userId: UUID, @Param("postId") postId: UUID)
}
