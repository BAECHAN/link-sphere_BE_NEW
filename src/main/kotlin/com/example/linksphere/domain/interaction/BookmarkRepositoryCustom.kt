package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.global.common.CursorKeyType
import com.example.linksphere.global.common.KeysetSlice
import java.util.UUID

interface BookmarkRepositoryCustom {
    /**
     * 본인의 북마크에 해당하는 Post를 cursor로 이어 읽는다. after(이전 쪽 마지막 행의 키 값) 다음부터 size개를 읽는다.
     * 첫 쪽이면 after는 null이다. withTotal이면 후보 건수를 함께 센다.
     *
     * folderId/onlyUncategorized 조합 (다중 폴더 소속 — bookmark_folder_items 기준):
     *  - folderId=null, onlyUncategorized=false → 전체 북마크 (소속 무관, 중복 없이 1회)
     *  - folderId=null, onlyUncategorized=true  → 미분류 (소속 0개, NOT EXISTS)
     *  - folderId=UUID, onlyUncategorized=false → 해당 폴더에 소속 (EXISTS)
     *
     * search: 제목/설명/태그 부분 검색 (null/blank면 미적용)
     *
     * Post visibility 적용: isPrivate=false OR post.userId=userId
     */
    fun findBookmarkedPostsAfter(
        userId: UUID,
        folderId: UUID?,
        onlyUncategorized: Boolean,
        sort: BookmarkListSort,
        search: String?,
        after: List<Comparable<*>>?,
        size: Int,
        queryEmbedding: FloatArray? = null,
        withTotal: Boolean = false,
    ): KeysetSlice<TablePost>
}

// 북마크 목록의 정렬과 cursor에 담는 키 구성. 키 순서는 BookmarkRepositoryImpl.findBookmarkedPostsAfter와 맞춘다
enum class BookmarkListSort(val keyTypes: List<CursorKeyType>) {
    LATEST(listOf(CursorKeyType.TIME, CursorKeyType.ID)),
    OLDEST(listOf(CursorKeyType.TIME, CursorKeyType.ID)),
    TITLE(listOf(CursorKeyType.TEXT, CursorKeyType.TIME, CursorKeyType.ID)),
    VIEWED(listOf(CursorKeyType.TIME, CursorKeyType.TIME, CursorKeyType.ID)),
    RELEVANCE(listOf(CursorKeyType.NUMBER, CursorKeyType.TIME, CursorKeyType.ID)),
    ;

    companion object {
        // 검색어가 있고 기본(latest) 정렬이면 관련도순, 그 외엔 사용자 선택 유지 - 모르는 값은 latest로 본다
        fun of(sort: String?, searching: Boolean): BookmarkListSort = when (sort ?: "latest") {
            "latest" -> if (searching) RELEVANCE else LATEST
            "oldest" -> OLDEST
            "title" -> TITLE
            "viewed" -> VIEWED
            else -> LATEST
        }
    }
}
