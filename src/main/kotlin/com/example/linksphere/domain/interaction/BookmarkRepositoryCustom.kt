package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.TablePost
import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface BookmarkRepositoryCustom {
    /**
     * 본인의 북마크에 해당하는 Post 페이지 반환.
     *
     * folderId/onlyUncategorized 조합:
     *  - folderId=null, onlyUncategorized=false → 전체 북마크
     *  - folderId=null, onlyUncategorized=true  → 미분류 (folder_id IS NULL)
     *  - folderId=UUID, onlyUncategorized=false → 해당 폴더
     *
     * sort: "latest"(default) / "oldest" / "title" / "views"
     *
     * Post visibility 적용: isPrivate=false OR post.userId=userId
     */
    fun findBookmarkedPosts(
            userId: UUID,
            folderId: UUID?,
            onlyUncategorized: Boolean,
            sort: String,
            pageable: Pageable
    ): Page<TablePost>
}
