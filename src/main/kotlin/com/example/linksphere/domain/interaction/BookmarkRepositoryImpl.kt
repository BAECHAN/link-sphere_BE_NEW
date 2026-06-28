package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.TablePost
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.Predicate
import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable

class BookmarkRepositoryImpl : BookmarkRepositoryCustom {

    @PersistenceContext private lateinit var entityManager: EntityManager

    override fun findBookmarkedPosts(
            userId: UUID,
            folderId: UUID?,
            onlyUncategorized: Boolean,
            sort: String,
            pageable: Pageable
    ): Page<TablePost> {
        val cb = entityManager.criteriaBuilder

        // 1) count query — bookmark 기준 (post와 1:1 매칭이므로 동일)
        val countQuery = cb.createQuery(Long::class.java)
        val countBookmarkRoot = countQuery.from(TableBookmark::class.java)
        val countPostJoin = countBookmarkRoot.join<TableBookmark, TablePost>("post", JoinType.INNER)
        countQuery
                .select(cb.count(countBookmarkRoot))
                .where(*buildPredicates(cb, countBookmarkRoot, countPostJoin, userId, folderId, onlyUncategorized).toTypedArray())
        val total = entityManager.createQuery(countQuery).singleResult

        if (total == 0L) return PageImpl(emptyList(), pageable, 0L)

        // 2) data query — Post 반환
        val query = cb.createQuery(TablePost::class.java)
        val bookmarkRoot = query.from(TableBookmark::class.java)
        val postJoin = bookmarkRoot.join<TableBookmark, TablePost>("post", JoinType.INNER)

        query
                .select(postJoin)
                .where(*buildPredicates(cb, bookmarkRoot, postJoin, userId, folderId, onlyUncategorized).toTypedArray())

        // sort
        val order = when (sort) {
            "oldest" -> cb.asc(bookmarkRoot.get<Any>("createdAt"))
            "title" -> cb.asc(postJoin.get<Any>("title"))
            "views" -> cb.desc(postJoin.get<Any>("viewCount"))
            else -> cb.desc(bookmarkRoot.get<Any>("createdAt")) // "latest" default
        }
        query.orderBy(order)

        val resultList =
                entityManager
                        .createQuery(query)
                        .setFirstResult(pageable.offset.toInt())
                        .setMaxResults(pageable.pageSize)
                        .resultList

        return PageImpl(resultList, pageable, total)
    }

    private fun buildPredicates(
            cb: jakarta.persistence.criteria.CriteriaBuilder,
            bookmarkRoot: jakarta.persistence.criteria.Root<TableBookmark>,
            postJoin: jakarta.persistence.criteria.Join<TableBookmark, TablePost>,
            userId: UUID,
            folderId: UUID?,
            onlyUncategorized: Boolean
    ): List<Predicate> {
        val predicates = mutableListOf<Predicate>()

        predicates.add(cb.equal(bookmarkRoot.get<UUID>("userId"), userId))

        when {
            onlyUncategorized -> predicates.add(cb.isNull(bookmarkRoot.get<UUID>("folderId")))
            folderId != null -> predicates.add(cb.equal(bookmarkRoot.get<UUID>("folderId"), folderId))
            // else: all — no folder filter
        }

        // Post visibility: isPrivate=false OR post.userId=currentUserId (북마크 소유자)
        val publicPredicate = cb.equal(postJoin.get<Boolean>("isPrivate"), false)
        val ownerPredicate = cb.equal(postJoin.get<UUID>("userId"), userId)
        predicates.add(cb.or(publicPredicate, ownerPredicate))

        return predicates
    }
}
