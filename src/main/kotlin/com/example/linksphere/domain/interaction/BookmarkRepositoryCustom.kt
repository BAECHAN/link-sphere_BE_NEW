package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.TablePost
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

interface BookmarkRepositoryCustom {
    /**
     * 본인의 북마크에 해당하는 Post 페이지 반환.
     *
     * folderId/onlyUncategorized 조합 (다중 폴더 소속 — bookmark_folder_items 기준):
     *  - folderId=null, onlyUncategorized=false → 전체 북마크 (소속 무관, 중복 없이 1회)
     *  - folderId=null, onlyUncategorized=true  → 미분류 (소속 0개, NOT EXISTS)
     *  - folderId=UUID, onlyUncategorized=false → 해당 폴더에 소속 (EXISTS)
     *
     * sort: "latest"(default) / "oldest" / "title" / "views"
     *
     * search: 제목/설명/태그 부분 검색 (null/blank면 미적용)
     *
     * Post visibility 적용: isPrivate=false OR post.userId=userId
     */
    fun findBookmarkedPosts(
        userId: UUID,
        folderId: UUID?,
        onlyUncategorized: Boolean,
        sort: String,
        search: String?,
        pageable: Pageable,
    ): Page<TablePost>
}
