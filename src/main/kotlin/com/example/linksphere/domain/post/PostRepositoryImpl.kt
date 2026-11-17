package com.example.linksphere.domain.post

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
        postIds: Set<UUID>?,
        authorIds: Set<UUID>?,
        excludedAuthorIds: Set<UUID>,
        currentUserId: UUID?,
        sort: PostListSort,
        after: List<Comparable<*>>?,
        size: Int,
        queryEmbedding: FloatArray?,
        withTotal: Boolean,
    ): KeysetSlice<TablePost> {
        // 북마크·좋아요한 글이 하나도 없으면 DB에 묻지 않는다 - 빈 IN 목록을 만들지 않고, 의미 검색 후보 조회도 건너뛴다
        if (postIds != null && postIds.isEmpty()) {
            return KeysetSlice(content = emptyList(), total = if (withTotal) 0L else null)
        }

        val cb = entityManager.criteriaBuilder
        // 건수와 본 쿼리가 같은 후보 집합을 보도록 한 번만 계산한다
        val semanticMatches = PostSearchQuery.resolveSemanticMatches(entityManager, queryEmbedding)
        val predicatesOf = { root: Root<TablePost>, query: AbstractQuery<*> ->
            buildPredicates(cb, root, query, categoryIds, search, filters, postIds, authorIds, excludedAuthorIds, currentUserId, semanticMatches)
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

    override fun rankVisibleAmong(
        candidateIds: Collection<UUID>,
        viewerId: UUID,
        order: PostRankOrder,
        search: String?,
        fromKey: Comparable<*>?,
        queryEmbedding: FloatArray?,
    ): List<RankedPost> {
        if (candidateIds.isEmpty()) {
            return emptyList()
        }

        val cb = entityManager.criteriaBuilder
        val semanticMatches = PostSearchQuery.resolveSemanticMatches(entityManager, queryEmbedding)
        val searchTokens = PostSearchQuery.tokenize(search)
        // 검색 점수는 비싸고, 제목 앞부분은 계산식이라 파생 테이블에서 한 번만 계산한다. 최근 열람순은 조건을 WHERE에 바로 쓴다
        // (직접 측정, docs/PAGINATION.md §5.2 - 북마크 목록이 한 문장이던 때와 같은 선택이다).
        val fetchRows = if (order == PostRankOrder.RELEVANCE || order == PostRankOrder.TITLE) KeysetQuery::derivedRows else KeysetQuery::directRows
        // "fromKey와 같거나 뒤"를 기존 seek 조건으로 쓴다 - 둘째 키(id 오름차순)에 가장 작은 id를 주면 키가 같은 글이 모두 남는다
        val after = fromKey?.takeIf { order != PostRankOrder.NONE }?.let { listOf(it, SMALLEST_ID) }
        val rows =
            fetchRows(entityManager, after, candidateIds.size) { query ->
                val root = query.from(TablePost::class.java)
                val firstKey =
                    when (order) {
                        // 제목은 길이 제한이 없고 cursor는 쿼리 문자열로 오간다. 앞 200자만 키로 써 WAF 2,048바이트 한도를 넘지 않게 한다.
                        PostRankOrder.TITLE -> SeekKey(cb.function("left", String::class.java, root.get<String>("title"), cb.literal(TITLE_KEY_LENGTH)), descending = false)
                        PostRankOrder.VIEWED -> {
                            // TablePost ↔ TablePostView는 연관관계로 매핑돼 있지 않아 상관 서브쿼리를 쓴다.
                            val viewedAtSub = query.subquery(LocalDateTime::class.java)
                            val viewRoot = viewedAtSub.from(TablePostView::class.java)
                            viewedAtSub.select(viewRoot.get("viewedAt"))
                            viewedAtSub.where(
                                cb.equal(viewRoot.get<UUID>("postId"), root.get<UUID>("id")),
                                cb.equal(viewRoot.get<UUID>("userId"), viewerId),
                            )
                            // 한 번도 안 본 글은 NULL → 아주 오래된 값으로 치환해 맨 뒤로 민다. NULL이면 비교에서 빠져 이어 읽지 못한다.
                            SeekKey(cb.coalesce(viewedAtSub, NEVER_VIEWED))
                        }
                        PostRankOrder.RELEVANCE -> SeekKey(PostSearchQuery.relevanceKey(cb, root, searchTokens, semanticMatches))
                        // 남은 값은 NONE이다. 값을 모두 적으면 컴파일러가 예외를 만드는 분기를 넣어 "저장소는 예외를 만들지 않는다" 규칙에 걸린다
                        else -> null
                    }
                val predicates = mutableListOf<Predicate>(root.get<UUID>("id").`in`(candidateIds))

                if (searchTokens.isNotEmpty()) {
                    predicates.add(PostSearchQuery.searchPredicate(cb, root, searchTokens, semanticMatches))
                }

                predicates.add(cb.or(cb.equal(root.get<Boolean>("isPrivate"), false), cb.equal(root.get<UUID>("userId"), viewerId)))

                KeysetSpec(listOfNotNull(firstKey, SeekKey(root.get<UUID>("id"), descending = false)), predicates)
            }

        return rows.map { row -> RankedPost(row.last() as UUID, row.takeIf { it.size > 1 }?.first()) }
    }

    private companion object {
        const val TITLE_KEY_LENGTH = 200
        val NEVER_VIEWED: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
        val SMALLEST_ID = UUID(0, 0)
    }

    private fun buildPredicates(
        cb: CriteriaBuilder,
        root: Root<TablePost>,
        query: AbstractQuery<*>,
        categoryIds: Set<Long>?,
        search: String?,
        filters: Set<String>,
        postIds: Set<UUID>?,
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

        // 내가 북마크·좋아요한 글만 - id 집합은 PostService가 interaction에 물어 넘긴다. 빈 집합은 findPostsAfter가 먼저 걸러 여기 오지 않는다
        if (postIds != null) {
            predicates.add(root.get<UUID>("id").`in`(postIds))
        }

        // Filters (Multiple supported) - 쉼표로 나누는 일은 PostService가 한다
        for (f in filters) {
            when (f) {
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
