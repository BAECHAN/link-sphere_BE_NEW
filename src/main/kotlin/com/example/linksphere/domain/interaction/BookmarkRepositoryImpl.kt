package com.example.linksphere.domain.interaction

import com.example.linksphere.global.common.KeysetQuery
import com.example.linksphere.global.common.KeysetSpec
import com.example.linksphere.global.common.SeekKey
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.criteria.AbstractQuery
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import java.time.LocalDateTime
import java.util.UUID

class BookmarkRepositoryImpl : BookmarkRepositoryCustom {

    @PersistenceContext private lateinit var entityManager: EntityManager

    override fun findBookmarkKeysAfter(
        userId: UUID,
        folderId: UUID?,
        onlyUncategorized: Boolean,
        descending: Boolean,
        after: List<Comparable<*>>?,
        limit: Int,
    ): List<BookmarkKey> {
        val cb = entityManager.criteriaBuilder
        // 첫 키(북마크 시각)에 인덱스가 있어 조건을 WHERE에 바로 쓴다 - 인덱스로 시작 지점을 찾는다(docs/PAGINATION.md §5.2)
        val rows =
            KeysetQuery.directRows(entityManager, after, limit) { query ->
                val bookmarkRoot = query.from(TableBookmark::class.java)

                KeysetSpec(
                    listOf(SeekKey(bookmarkRoot.get<LocalDateTime>("createdAt"), descending), SeekKey(bookmarkRoot.get<UUID>("postId"), descending)),
                    buildPredicates(cb, query, bookmarkRoot, userId, folderId, onlyUncategorized),
                )
            }

        return rows.map { BookmarkKey(it[0] as LocalDateTime, it[1] as UUID) }
    }

    private fun buildPredicates(
        cb: CriteriaBuilder,
        query: AbstractQuery<*>,
        bookmarkRoot: Root<TableBookmark>,
        userId: UUID,
        folderId: UUID?,
        onlyUncategorized: Boolean,
    ): List<Predicate> {
        val predicates = mutableListOf<Predicate>()

        predicates.add(cb.equal(bookmarkRoot.get<UUID>("userId"), userId))

        // 폴더 필터 — bookmark_folder_items 상관 서브쿼리 (EXISTS/NOT EXISTS). 세미조인은 row를 증식시키지 않으므로
        // DISTINCT 없이도 중복이 발생하지 않는다.
        if (folderId != null || onlyUncategorized) {
            val sub = query.subquery(UUID::class.java)
            val item = sub.from(TableBookmarkFolderItem::class.java)
            val correlated = sub.correlate(bookmarkRoot)
            sub.select(item.get<UUID>("postId"))

            val subConditions =
                mutableListOf(
                    cb.equal(item.get<UUID>("userId"), userId),
                    cb.equal(item.get<UUID>("postId"), correlated.get<UUID>("postId")),
                )
            folderId?.let { subConditions.add(cb.equal(item.get<UUID>("folderId"), it)) }
            sub.where(*subConditions.toTypedArray())

            predicates.add(if (onlyUncategorized) cb.not(cb.exists(sub)) else cb.exists(sub))
        }

        return predicates
    }
}
