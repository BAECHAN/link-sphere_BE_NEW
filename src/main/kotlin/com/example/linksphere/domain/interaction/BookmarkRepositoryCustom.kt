package com.example.linksphere.domain.interaction

import com.example.linksphere.global.common.CursorKeyType
import java.time.LocalDateTime
import java.util.UUID

/** 북마크 한 건의 정렬 키 - 북마크한 시각과 글 id(복합 PK라 단일 id가 없어 글 id로 동점을 끊는다). */
data class BookmarkKey(val createdAt: LocalDateTime, val postId: UUID)

interface BookmarkRepositoryCustom {
    /**
     * 본인의 북마크를 북마크한 시각 순으로 이어 읽는다(글 테이블은 읽지 않는다). after(앞서 읽은 마지막 행의 [시각, 글 id]) 다음부터
     * limit개를 읽는다. 처음이면 after는 null이다. 글이 지금 보이는지는 묻는 쪽이 post에 확인한다(BookmarkedPostFinder).
     *
     * folderId/onlyUncategorized 조합 (다중 폴더 소속 — bookmark_folder_items 기준):
     *  - folderId=null, onlyUncategorized=false → 전체 북마크 (소속 무관, 중복 없이 1회)
     *  - folderId=null, onlyUncategorized=true  → 미분류 (소속 0개, NOT EXISTS)
     *  - folderId=UUID, onlyUncategorized=false → 해당 폴더에 소속 (EXISTS)
     */
    fun findBookmarkKeysAfter(
        userId: UUID,
        folderId: UUID?,
        onlyUncategorized: Boolean,
        descending: Boolean,
        after: List<Comparable<*>>?,
        limit: Int,
    ): List<BookmarkKey>
}

// 북마크 목록의 정렬과 cursor에 담는 키 구성. 키 순서는 BookmarkedPostFinder.findAfter와 맞춘다
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
