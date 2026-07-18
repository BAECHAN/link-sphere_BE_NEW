package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.TableCategory
import com.example.linksphere.domain.interaction.TableBookmark
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.criteria.*
import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable

class PostRepositoryImpl : PostRepositoryCustom {

        @PersistenceContext private lateinit var entityManager: EntityManager

        override fun findPosts(
                category: String?,
                search: String?,
                filter: String?,
                nickname: String?,
                currentUserId: UUID?,
                pageable: Pageable
        ): Page<TablePost> {
                val cb = entityManager.criteriaBuilder

                // 1. Create count query
                val countQuery = cb.createQuery(Long::class.java)
                val countRoot = countQuery.from(TablePost::class.java)

                val countPredicates =
                        buildPredicates(
                                cb,
                                countRoot,
                                countQuery,
                                category,
                                search,
                                filter,
                                nickname,
                                currentUserId
                        )
                countQuery
                        .select(cb.countDistinct(countRoot))
                        .where(*countPredicates.toTypedArray())

                val total = entityManager.createQuery(countQuery).singleResult

                // 2. Create data query
                val query = cb.createQuery(TablePost::class.java)
                val root = query.from(TablePost::class.java)

                val predicates =
                        buildPredicates(
                                cb,
                                root,
                                query,
                                category,
                                search,
                                filter,
                                nickname,
                                currentUserId
                        )
                query.select(root).where(*predicates.toTypedArray())

                // Sort order — 검색어가 있으면 관련도순, 없으면 기존 정렬
                val searchTokens = PostSearchQuery.tokenize(search)
                if (searchTokens.isNotEmpty()) {
                        query.orderBy(
                                cb.desc(PostSearchQuery.relevanceScore(cb, root, searchTokens)),
                                cb.desc(root.get<Any>("createdAt"))
                        )
                } else if (pageable.sort.isSorted) {
                        val orders =
                                pageable.sort
                                        .map { order ->
                                                if (order.isAscending)
                                                        cb.asc(root.get<Any>(order.property))
                                                else cb.desc(root.get<Any>(order.property))
                                        }
                                        .toList()
                        query.orderBy(orders)
                } else {
                        query.orderBy(cb.desc(root.get<Any>("createdAt")))
                }

                val resultList =
                        entityManager
                                .createQuery(query)
                                .setFirstResult(pageable.offset.toInt())
                                .setMaxResults(pageable.pageSize)
                                .resultList

                return PageImpl(resultList, pageable, total)
        }

        private fun buildPredicates(
                cb: CriteriaBuilder,
                root: Root<TablePost>,
                query: CriteriaQuery<*>,
                category: String?,
                search: String?,
                filter: String?,
                nickname: String?,
                currentUserId: UUID?
        ): List<Predicate> {
                val predicates = mutableListOf<Predicate>()

                // Category Filter (Multiple supported, comma separated)
                if (!category.isNullOrBlank()) {
                        val categories =
                                category.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                        if (categories.isNotEmpty()) {
                                // EXISTS 서브쿼리 — INNER JOIN fan-out(중복 행) 방지, distinct 불필요
                                val subquery = query.subquery(Int::class.java)
                                val subRoot = subquery.from(TablePost::class.java)
                                val categoriesJoin: Join<TablePost, TableCategory> =
                                        subRoot.join("categories", JoinType.INNER)

                                val categoryPredicates =
                                        categories.map { cat ->
                                                cb.or(
                                                        cb.equal(
                                                                categoriesJoin.get<String>("name"),
                                                                cat
                                                        ),
                                                        cb.equal(
                                                                categoriesJoin.get<String>("slug"),
                                                                cat
                                                        )
                                                )
                                        }
                                subquery.select(cb.literal(1))
                                        .where(
                                                cb.equal(
                                                        subRoot.get<UUID>("id"),
                                                        root.get<UUID>("id")
                                                ),
                                                cb.or(*categoryPredicates.toTypedArray())
                                        )
                                predicates.add(cb.exists(subquery))
                        }
                }

                // Search Filter (Title, Description, or Tags) — 토큰 분리 후 OR 매칭
                val searchTokens = PostSearchQuery.tokenize(search)
                if (searchTokens.isNotEmpty()) {
                        predicates.add(PostSearchQuery.searchPredicate(cb, root, searchTokens))
                }

                // Nickname Filter (Partial Match)
                if (!nickname.isNullOrBlank()) {
                        val subquery = query.subquery(UUID::class.java)
                        val memberRoot =
                                subquery.from(
                                        com.example.linksphere.domain.member.TableMember::class.java
                                )
                        subquery.select(memberRoot.get("id"))
                        subquery.where(
                                cb.like(
                                        cb.lower(memberRoot.get("nickname")),
                                        "%${nickname.lowercase()}%"
                                )
                        )
                        predicates.add(root.get<UUID>("userId").`in`(subquery))
                }

                // Filters (Multiple supported, comma separated)
                if (!filter.isNullOrBlank()) {
                        val activeFilters =
                                filter.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                        for (f in activeFilters) {
                                when (f) {
                                        "isBookmarked" -> {
                                                if (currentUserId != null) {
                                                        val subquery =
                                                                query.subquery(UUID::class.java)
                                                        val bookmarkRoot =
                                                                subquery.from(
                                                                        TableBookmark::class.java
                                                                )
                                                        subquery.select(bookmarkRoot.get("postId"))
                                                        subquery.where(
                                                                cb.equal(
                                                                        bookmarkRoot.get<UUID>(
                                                                                "userId"
                                                                        ),
                                                                        currentUserId
                                                                )
                                                        )
                                                        // Post ID must be in the list of bookmarked
                                                        // Post IDs
                                                        predicates.add(
                                                                root.get<UUID>("id").`in`(subquery)
                                                        )
                                                }
                                        }
                                        "isMyPosts" -> {
                                                if (currentUserId != null) {
                                                        predicates.add(
                                                                cb.equal(
                                                                        root.get<UUID>("userId"),
                                                                        currentUserId
                                                                )
                                                        )
                                                }
                                        }
                                        "isPrivate" -> {
                                                if (currentUserId != null) {
                                                        predicates.add(
                                                                cb.equal(
                                                                        root.get<Boolean>(
                                                                                "isPrivate"
                                                                        ),
                                                                        true
                                                                )
                                                        )
                                                } else {
                                                        predicates.add(cb.disjunction())
                                                }
                                        }
                                }
                        }
                }

                // --- Visibility Filter ---
                // 1. Show if is_private is false (Public)
                // 2. OR show if userId matches currentUserId (Owner)
                val publicPredicate = cb.equal(root.get<Boolean>("isPrivate"), false)
                if (currentUserId != null) {
                        val ownerPredicate = cb.equal(root.get<UUID>("userId"), currentUserId)
                        predicates.add(cb.or(publicPredicate, ownerPredicate))
                } else {
                        predicates.add(publicPredicate)
                }

                return predicates
        }
}
