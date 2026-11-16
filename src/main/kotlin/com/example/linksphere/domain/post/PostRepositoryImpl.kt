package com.example.linksphere.domain.post

import com.example.linksphere.domain.interaction.TableBookmark
import com.example.linksphere.domain.interaction.TablePostReaction
import com.example.linksphere.global.common.KeysetQuery
import com.example.linksphere.global.common.KeysetSlice
import com.example.linksphere.global.common.KeysetSpec
import com.example.linksphere.global.common.SeekKey
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.criteria.*
import java.time.LocalDateTime
import java.util.UUID

class PostRepositoryImpl : PostRepositoryCustom {

    @PersistenceContext private lateinit var entityManager: EntityManager

    override fun findPostsAfter(
        categoryIds: Set<Long>?,
        search: String?,
        filters: Set<String>,
        authorIds: Set<UUID>?,
        excludedAuthorIds: Set<UUID>,
        currentUserId: UUID?,
        sort: PostListSort,
        after: List<Comparable<*>>?,
        size: Int,
        queryEmbedding: FloatArray?,
        withTotal: Boolean,
    ): KeysetSlice<TablePost> {
        val cb = entityManager.criteriaBuilder
        // 건수와 본 쿼리가 같은 후보 집합을 보도록 한 번만 계산한다
        val semanticMatches = PostSearchQuery.resolveSemanticMatches(entityManager, queryEmbedding)
        val predicatesOf = { root: Root<TablePost>, query: AbstractQuery<*> ->
            buildPredicates(cb, root, query, categoryIds, search, filters, authorIds, excludedAuthorIds, currentUserId, semanticMatches)
        }

        val total =
            if (withTotal) {
                val countQuery = cb.createQuery(Long::class.java)
                val countRoot = countQuery.from(TablePost::class.java)
                countQuery.select(cb.countDistinct(countRoot)).where(*predicatesOf(countRoot, countQuery).toTypedArray())
                entityManager.createQuery(countQuery).singleResult
            } else {
                null
            }

        // 최신순은 첫 키에 인덱스가 있고, 검색은 첫 키가 비싼 계산식이다
        val fetchRows = if (sort == PostListSort.LATEST) KeysetQuery::directRows else KeysetQuery::derivedRows
        val rows =
            fetchRows(entityManager, after, size + 1) { query ->
                val root = query.from(TablePost::class.java)
                val score = if (sort == PostListSort.RELEVANCE) SeekKey(PostSearchQuery.relevanceKey(cb, root, PostSearchQuery.tokenize(search), semanticMatches)) else null
                KeysetSpec(listOfNotNull(score, SeekKey(root.get<LocalDateTime>("createdAt")), SeekKey(root.get<UUID>("id"))), predicatesOf(root, query))
            }

        return KeysetQuery.slice(entityManager, TablePost::class.java, rows, size, total) { it.id }
    }

    private fun buildPredicates(
        cb: CriteriaBuilder,
        root: Root<TablePost>,
        query: AbstractQuery<*>,
        categoryIds: Set<Long>?,
        search: String?,
        filters: Set<String>,
        authorIds: Set<UUID>?,
        excludedAuthorIds: Set<UUID>,
        currentUserId: UUID?,
        semanticMatches: Map<UUID, Double>,
    ): List<Predicate> {
        val predicates = mutableListOf<Predicate>()

        // 카테고리 필터 - 고른 카테고리 중 하나라도 붙은 글. 빈 집합(이름·slug가 하나도 안 맞음)이면 결과가 없어야 한다 -
        // 조건을 빼면 전체 글이 나온다.
        if (categoryIds != null) {
            if (categoryIds.isEmpty()) {
                predicates.add(cb.disjunction())
            } else {
                // EXISTS 서브쿼리 — INNER JOIN fan-out(중복 행) 방지, distinct 불필요
                val subquery = query.subquery(Int::class.java)
                val subRoot = subquery.from(TablePost::class.java)
                val categoryId: Join<TablePost, Long> = subRoot.join("categoryIds", JoinType.INNER)

                subquery.select(cb.literal(1))
                    .where(
                        cb.equal(
                            subRoot.get<UUID>("id"),
                            root.get<UUID>("id"),
                        ),
                        categoryId.`in`(categoryIds),
                    )
                predicates.add(cb.exists(subquery))
            }
        }

        // Search Filter (Title, Description, Tags, AI 요약) — 토큰 분리 후 OR 매칭 + 의미 매칭
        val searchTokens = PostSearchQuery.tokenize(search)
        if (searchTokens.isNotEmpty()) {
            predicates.add(PostSearchQuery.searchPredicate(cb, root, searchTokens, semanticMatches))
        }

        // 작성자 필터(닉네임 검색) - 맞는 회원이 없으면(빈 집합) 결과가 없어야 한다. 조건을 빼면 전체 글이 나온다.
        if (authorIds != null) {
            predicates.add(if (authorIds.isEmpty()) cb.disjunction() else root.get<UUID>("userId").`in`(authorIds))
        }

        // 뺄 작성자(봇 제외) - 봇이 없으면 조건을 걸지 않는다
        if (excludedAuthorIds.isNotEmpty()) {
            predicates.add(cb.not(root.get<UUID>("userId").`in`(excludedAuthorIds)))
        }

        // Filters (Multiple supported) - 쉼표로 나누는 일은 PostService가 한다
        for (f in filters) {
            when (f) {
                "isBookmarked" -> {
                    if (currentUserId != null) {
                        val subquery =
                            query.subquery(UUID::class.java)
                        val bookmarkRoot =
                            subquery.from(
                                TableBookmark::class.java,
                            )
                        subquery.select(bookmarkRoot.get("postId"))
                        subquery.where(
                            cb.equal(
                                bookmarkRoot.get<UUID>(
                                    "userId",
                                ),
                                currentUserId,
                            ),
                        )
                        // Post ID must be in the list of bookmarked
                        // Post IDs
                        predicates.add(
                            root.get<UUID>("id").`in`(subquery),
                        )
                    }
                }
                "isLiked" -> {
                    // isBookmarked와 같은 형태 — 내가 좋아요한(post_reactions) 글만 남긴다
                    if (currentUserId != null) {
                        val subquery =
                            query.subquery(UUID::class.java)
                        val reactionRoot =
                            subquery.from(
                                TablePostReaction::class.java,
                            )
                        subquery.select(reactionRoot.get("postId"))
                        subquery.where(
                            cb.equal(
                                reactionRoot.get<UUID>(
                                    "userId",
                                ),
                                currentUserId,
                            ),
                        )
                        predicates.add(
                            root.get<UUID>("id").`in`(subquery),
                        )
                    }
                }
                "isMyPosts" -> {
                    if (currentUserId != null) {
                        predicates.add(
                            cb.equal(
                                root.get<UUID>("userId"),
                                currentUserId,
                            ),
                        )
                    }
                }
                "isPrivate" -> {
                    if (currentUserId != null) {
                        predicates.add(
                            cb.equal(
                                root.get<Boolean>(
                                    "isPrivate",
                                ),
                                true,
                            ),
                        )
                    } else {
                        predicates.add(cb.disjunction())
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
