package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.PostCandidateService
import com.example.linksphere.domain.post.PostRankOrder
import com.example.linksphere.domain.post.PostSearchQuery
import com.example.linksphere.domain.post.RankedPost
import com.example.linksphere.global.common.KeysetSlice
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID

/**
 * 북마크한 글의 id를 cursor로 이어 읽는다. 북마크 테이블은 interaction이 읽고, 글이 보이는지·검색에 걸리는지·글 쪽 정렬 키는
 * post에 묻는다(PostCandidateService) - 예전에는 한 문장이 bookmarks와 posts를 조인했다(docs/plans/2026-11-16-be-structure-stage2.md PR-9c).
 *
 * 순서와 cursor는 그때와 같다: [첫 키(정렬마다 다르고 최신순·오래된순은 없다), 북마크 시각, 글 id]. 경로는 둘이다.
 *  - 최신순·오래된순이고 검색이 없으면: 북마크를 시각 순으로 읽고(인덱스) post에 보이는 글을 물어, 빠진 만큼 더 읽어 채운다.
 *  - 그 밖(제목·열람·관련도, 검색): 후보 id를 모두 post에 넘겨 첫 키 순서로 받고, 키가 같은 글끼리만 북마크 시각으로 순서를 맞춘다.
 */
@Component
class BookmarkedPostFinder(
    private val bookmarkRepository: BookmarkRepository,
    private val postCandidateService: PostCandidateService,
) {

    /** after(이전 쪽 마지막 글의 키 값) 다음부터 size개의 글 id를 돌려준다. 첫 쪽이면 after는 null이다. withTotal이면 후보 건수를 함께 센다. */
    fun findAfter(
        userId: UUID,
        folderId: UUID?,
        onlyUncategorized: Boolean,
        sort: BookmarkListSort,
        search: String?,
        after: List<Comparable<*>>?,
        size: Int,
        queryEmbedding: FloatArray? = null,
        withTotal: Boolean = false,
    ): KeysetSlice<UUID> {
        val descending = sort != BookmarkListSort.OLDEST
        val searching = PostSearchQuery.tokenize(search).isNotEmpty()

        return if (!searching && (sort == BookmarkListSort.LATEST || sort == BookmarkListSort.OLDEST)) {
            readInBookmarkOrder(userId, folderId, onlyUncategorized, descending, after, size, withTotal)
        } else {
            readInPostOrder(userId, folderId, onlyUncategorized, sort, descending, search, after, size, queryEmbedding, withTotal)
        }
    }

    private fun readInBookmarkOrder(
        userId: UUID,
        folderId: UUID?,
        onlyUncategorized: Boolean,
        descending: Boolean,
        after: List<Comparable<*>>?,
        size: Int,
        withTotal: Boolean,
    ): KeysetSlice<UUID> {
        val page = mutableListOf<BookmarkKey>()
        var readAfter = after
        var readSize = size + 1
        var hasMore = false

        while (true) {
            val rows = bookmarkRepository.findBookmarkKeysAfter(userId, folderId, onlyUncategorized, descending, readAfter, readSize)
            val visibleIds = postCandidateService.findVisibleIds(rows.map { it.postId }, userId)

            for (row in rows) {
                if (row.postId !in visibleIds) {
                    continue
                }

                // 쪽이 찼는데 보이는 글이 하나 더 있다 - 그 글은 다음 쪽의 첫 행이다
                if (page.size == size) {
                    hasMore = true
                    break
                }

                page.add(row)
            }

            // 달라고 한 만큼 못 받았으면 더 읽을 북마크가 없다
            if (hasMore || rows.size < readSize) {
                break
            }

            readAfter = rows.last().let { listOf(it.createdAt, it.postId) }
            readSize = maxOf(size + 1, REFILL_ROWS)
        }

        val total =
            if (withTotal) {
                val allIds = bookmarkRepository.findBookmarkKeysAfter(userId, folderId, onlyUncategorized, descending, null, Int.MAX_VALUE).map { it.postId }
                postCandidateService.findVisibleIds(allIds, userId).size.toLong()
            } else {
                null
            }

        return KeysetSlice(page.map { it.postId }, page.lastOrNull()?.takeIf { hasMore }?.let { listOf(it.createdAt, it.postId) }, total)
    }

    private fun readInPostOrder(
        userId: UUID,
        folderId: UUID?,
        onlyUncategorized: Boolean,
        sort: BookmarkListSort,
        descending: Boolean,
        search: String?,
        after: List<Comparable<*>>?,
        size: Int,
        queryEmbedding: FloatArray?,
        withTotal: Boolean,
    ): KeysetSlice<UUID> {
        val bookmarkedAt =
            bookmarkRepository.findBookmarkKeysAfter(userId, folderId, onlyUncategorized, descending, null, Int.MAX_VALUE).associate { it.postId to it.createdAt }
        val order =
            when (sort) {
                BookmarkListSort.TITLE -> PostRankOrder.TITLE
                BookmarkListSort.VIEWED -> PostRankOrder.VIEWED
                BookmarkListSort.RELEVANCE -> PostRankOrder.RELEVANCE
                else -> PostRankOrder.NONE
            }
        val hasFirstKey = order != PostRankOrder.NONE
        val afterFirstKey = after?.takeIf { hasFirstKey }?.first()
        val afterBookmark = after?.let { BookmarkKey(it[it.size - 2] as LocalDateTime, it.last() as UUID) }
        val bookmarkOrder = bookmarkOrder(descending)

        val ranked = postCandidateService.rankVisible(bookmarkedAt.keys, userId, order, search, afterFirstKey, queryEmbedding)
        // 첫 키의 순서는 DB가 정했다. 키가 같은 글끼리만 북마크 시각·글 id로 줄 세운다(예전 쿼리의 둘째·셋째 정렬 키)
        val ordered =
            groupBySameKey(ranked).flatMap { group ->
                group.map { OrderedRow(it.key, BookmarkKey(bookmarkedAt.getValue(it.postId), it.postId)) }.sortedWith { a, b -> bookmarkOrder.compare(a.bookmark, b.bookmark) }
            }
        // post는 after의 첫 키와 같은 글을 모두 돌려준다. 그 가운데 이미 읽은 글(after와 같거나 앞)을 뺀다
        val remaining =
            if (afterBookmark == null) {
                ordered
            } else {
                ordered.filter { (hasFirstKey && !sameKey(it.key, afterFirstKey)) || bookmarkOrder.compare(it.bookmark, afterBookmark) > 0 }
            }
        val page = remaining.take(size)
        val nextKeys = page.lastOrNull()?.takeIf { remaining.size > size }?.let { listOfNotNull(it.key, it.bookmark.createdAt, it.bookmark.postId) }
        val total =
            when {
                !withTotal -> null
                // 첫 쪽이면 post가 후보 전체를 돌려줬다
                after == null -> ranked.size.toLong()
                else -> postCandidateService.rankVisible(bookmarkedAt.keys, userId, order, search, null, queryEmbedding).size.toLong()
            }

        return KeysetSlice(page.map { it.bookmark.postId }, nextKeys, total)
    }

    private class OrderedRow(val key: Comparable<*>?, val bookmark: BookmarkKey)

    private fun groupBySameKey(ranked: List<RankedPost>): List<List<RankedPost>> {
        val groups = mutableListOf<MutableList<RankedPost>>()

        for (row in ranked) {
            val last = groups.lastOrNull()

            if (last != null && sameKey(last.first().key, row.key)) {
                last.add(row)
            } else {
                groups.add(mutableListOf(row))
            }
        }

        return groups
    }

    // 검색 점수 키는 DB가 준 값과 cursor에서 읽은 값의 숫자 타입이 다를 수 있어 값으로 비교한다
    private fun sameKey(a: Comparable<*>?, b: Comparable<*>?): Boolean = if (a is Number && b is Number) a.toLong() == b.toLong() else a == b

    // 북마크 시각, 글 id 순(둘 다 같은 방향). 글 id는 Postgres의 uuid 순서(바이트 순)와 같게 부호 없이 비교한다 - UUID.compareTo는 부호 있는 비교라 순서가 다르다
    private fun bookmarkOrder(descending: Boolean): Comparator<BookmarkKey> {
        val ascending =
            compareBy<BookmarkKey> { it.createdAt }
                .thenComparing { a, b -> java.lang.Long.compareUnsigned(a.postId.mostSignificantBits, b.postId.mostSignificantBits) }
                .thenComparing { a, b -> java.lang.Long.compareUnsigned(a.postId.leastSignificantBits, b.postId.leastSignificantBits) }

        return if (descending) ascending.reversed() else ascending
    }

    private companion object {
        // 보이지 않는 글 때문에 쪽이 덜 찼을 때 다시 읽는 단위(내 댓글의 MY_COMMENTS_REFILL_ROWS와 같은 값)
        const val REFILL_ROWS = 51
    }
}
