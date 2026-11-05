package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.PostSearchQuery
import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.domain.post.TablePostView
import com.example.linksphere.global.common.KeysetQuery
import com.example.linksphere.global.common.KeysetSlice
import com.example.linksphere.global.common.KeysetSpec
import com.example.linksphere.global.common.SeekKey
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.LocalDateTime
import java.util.UUID

class BookmarkRepositoryImpl : BookmarkRepositoryCustom {

    @PersistenceContext private lateinit var entityManager: EntityManager

    override fun findBookmarkedPosts(
        userId: UUID,
        folderId: UUID?,
        onlyUncategorized: Boolean,
        sort: String,
        search: String?,
        pageable: Pageable,
        queryEmbedding: FloatArray?,
    ): Page<TablePost> {
        val cb = entityManager.criteriaBuilder
        // count/data 쿼리가 정확히 같은 후보 집합을 봐야 총 건수가 실제 반환 건수와
        // 일치한다 - 여기서 한 번만 계산해 양쪽에 그대로 넘긴다.
        val semanticMatches = PostSearchQuery.resolveSemanticMatches(entityManager, queryEmbedding)

        // 1) count query — bookmark 기준 (post와 1:1 매칭이므로 동일). 폴더 필터는 EXISTS 세미조인이라 row가 증식하지 않는다.
        val countQuery = cb.createQuery(Long::class.java)
        val countBookmarkRoot = countQuery.from(TableBookmark::class.java)
        val countPostJoin = countBookmarkRoot.join<TableBookmark, TablePost>("post", JoinType.INNER)
        countQuery
            .select(cb.count(countBookmarkRoot))
            .where(
                *buildPredicates(
                    cb,
                    countQuery,
                    countBookmarkRoot,
                    countPostJoin,
                    userId,
                    folderId,
                    onlyUncategorized,
                    search,
                    semanticMatches,
                ).toTypedArray(),
            )
        val total = entityManager.createQuery(countQuery).singleResult

        if (total == 0L) return PageImpl(emptyList(), pageable, 0L)

        // 2) data query — Post 반환
        val query = cb.createQuery(TablePost::class.java)
        val bookmarkRoot = query.from(TableBookmark::class.java)
        val postJoin = bookmarkRoot.join<TableBookmark, TablePost>("post", JoinType.INNER)

        query
            .select(postJoin)
            .where(
                *buildPredicates(
                    cb,
                    query,
                    bookmarkRoot,
                    postJoin,
                    userId,
                    folderId,
                    onlyUncategorized,
                    search,
                    semanticMatches,
                ).toTypedArray(),
            )

        // sort — 검색어가 있고 기본(latest) 정렬이면 관련도순, 그 외엔 사용자 선택 유지
        // 모든 뷰(전체/미분류/폴더)에서 "최신순"은 bookmarks.created_at 기준 — 폴더별 소속 시각이 아니다.
        val searchTokens = PostSearchQuery.tokenize(search)
        val orders =
            if (searchTokens.isNotEmpty() && sort == "latest") {
                listOf(
                    cb.desc(PostSearchQuery.relevanceScore(cb, postJoin, searchTokens, semanticMatches)),
                    cb.desc(bookmarkRoot.get<Any>("createdAt")),
                    cb.desc(bookmarkRoot.get<Any>("postId")),
                )
            } else {
                when (sort) {
                    "oldest" -> listOf(cb.asc(bookmarkRoot.get<Any>("createdAt")), cb.asc(bookmarkRoot.get<Any>("postId")))
                    "title" -> listOf(cb.asc(postJoin.get<Any>("title")))
                    "views" -> listOf(cb.desc(postJoin.get<Any>("viewCount")))
                    "viewed" -> {
                        // TablePost ↔ TablePostView는 연관관계로 매핑돼 있지 않아 JOIN 대신
                        // buildPredicates의 폴더 EXISTS 필터와 같은 상관 서브쿼리 모양을 쓴다.
                        val viewedAtSub = query.subquery(LocalDateTime::class.java)
                        val viewRoot = viewedAtSub.from(TablePostView::class.java)
                        viewedAtSub.select(viewRoot.get("viewedAt"))
                        viewedAtSub.where(
                            cb.equal(viewRoot.get<UUID>("postId"), postJoin.get<UUID>("id")),
                            cb.equal(viewRoot.get<UUID>("userId"), userId),
                        )
                        listOf(
                            // 한 번도 안 본 글은 NULL → 아주 오래된 값으로 치환해 DESC 정렬 시 맨 뒤로 밀린다
                            cb.desc(cb.coalesce(viewedAtSub, LocalDateTime.of(1970, 1, 1, 0, 0))),
                            // 미열람 글끼리는 전부 동점(위 값이 똑같음)이라 DB가 순서를 보장하지
                            // 않는다 — 2차 정렬로 안정시킨다("latest"와 동일 기준: 최근 북마크순).
                            // createdAt도 동점이면 postId로 최종 결정 — 복합 PK라 단일 id 없음
                            cb.desc(bookmarkRoot.get<Any>("createdAt")),
                            cb.desc(bookmarkRoot.get<Any>("postId")),
                        )
                    }
                    else -> listOf(cb.desc(bookmarkRoot.get<Any>("createdAt")), cb.desc(bookmarkRoot.get<Any>("postId"))) // "latest" default
                }
            }
        query.orderBy(orders)

        val resultList =
            entityManager
                .createQuery(query)
                .setFirstResult(pageable.offset.toInt())
                .setMaxResults(pageable.pageSize)
                .resultList

        return PageImpl(resultList, pageable, total)
    }

    override fun findBookmarkedPostsAfter(
        userId: UUID,
        folderId: UUID?,
        onlyUncategorized: Boolean,
        sort: BookmarkListSort,
        search: String?,
        after: List<Comparable<*>>?,
        size: Int,
        queryEmbedding: FloatArray?,
        withTotal: Boolean,
    ): KeysetSlice<TablePost> {
        val cb = entityManager.criteriaBuilder
        // 건수와 본 쿼리가 같은 후보 집합을 보도록 한 번만 계산한다 - findBookmarkedPosts와 같다
        val semanticMatches = PostSearchQuery.resolveSemanticMatches(entityManager, queryEmbedding)

        val total =
            if (withTotal) {
                val countQuery = cb.createQuery(Long::class.java)
                val countBookmarkRoot = countQuery.from(TableBookmark::class.java)
                val countPostJoin = countBookmarkRoot.join<TableBookmark, TablePost>("post", JoinType.INNER)
                countQuery
                    .select(cb.count(countBookmarkRoot))
                    .where(*buildPredicates(cb, countQuery, countBookmarkRoot, countPostJoin, userId, folderId, onlyUncategorized, search, semanticMatches).toTypedArray())
                entityManager.createQuery(countQuery).singleResult
            } else {
                null
            }

        // 검색 점수는 비싸고, 제목은 글 표의 컬럼이라 WHERE에 쓰면 조건이 글 표 탐색으로 내려가 앞쪽 위치일수록 느리다(50번째 1.8배).
        // 최신순·오래된순은 인덱스를 타고, 최근 열람순은 조인 전에 거를 수 있다(직접 측정, docs/PAGINATION.md §5.2).
        val fetchRows = if (sort == BookmarkListSort.RELEVANCE || sort == BookmarkListSort.TITLE) KeysetQuery::derivedRows else KeysetQuery::directRows
        val rows =
            fetchRows(entityManager, after, size + 1) { query ->
                val bookmarkRoot = query.from(TableBookmark::class.java)
                val postJoin = bookmarkRoot.join<TableBookmark, TablePost>("post", JoinType.INNER)
                val firstKey =
                    when (sort) {
                        // 제목은 길이 제한이 없고 cursor는 쿼리 문자열로 오간다. 앞 200자만 키로 써 WAF 2,048바이트 한도를 넘지 않게 한다.
                        // 앞 200자가 같은 글끼리는 북마크 시각으로 순서가 정해진다.
                        BookmarkListSort.TITLE -> SeekKey(cb.function("left", String::class.java, postJoin.get<String>("title"), cb.literal(TITLE_KEY_LENGTH)), descending = false)
                        BookmarkListSort.VIEWED -> {
                            // TablePost ↔ TablePostView는 연관관계로 매핑돼 있지 않아 상관 서브쿼리를 쓴다.
                            val viewedAtSub = query.subquery(LocalDateTime::class.java)
                            val viewRoot = viewedAtSub.from(TablePostView::class.java)
                            viewedAtSub.select(viewRoot.get("viewedAt"))
                            viewedAtSub.where(
                                cb.equal(viewRoot.get<UUID>("postId"), bookmarkRoot.get<UUID>("postId")),
                                cb.equal(viewRoot.get<UUID>("userId"), userId),
                            )
                            // 한 번도 안 본 글은 NULL → 아주 오래된 값으로 치환해 맨 뒤로 민다. NULL이면 비교에서 빠져 이어 읽지 못한다.
                            SeekKey(cb.coalesce(viewedAtSub, NEVER_VIEWED))
                        }
                        BookmarkListSort.RELEVANCE -> SeekKey(PostSearchQuery.relevanceKey(cb, postJoin, PostSearchQuery.tokenize(search), semanticMatches))
                        else -> null
                    }
                // 복합 PK라 단일 id가 없어 post_id로 동점을 끊는다
                val descending = sort != BookmarkListSort.OLDEST
                KeysetSpec(
                    listOfNotNull(firstKey, SeekKey(bookmarkRoot.get<LocalDateTime>("createdAt"), descending), SeekKey(bookmarkRoot.get<UUID>("postId"), descending)),
                    buildPredicates(cb, query, bookmarkRoot, postJoin, userId, folderId, onlyUncategorized, search, semanticMatches),
                )
            }

        return KeysetQuery.slice(entityManager, TablePost::class.java, rows, size, total) { it.id }
    }

    private companion object {
        const val TITLE_KEY_LENGTH = 200
        val NEVER_VIEWED: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
    }

    private fun buildPredicates(
        cb: jakarta.persistence.criteria.CriteriaBuilder,
        query: jakarta.persistence.criteria.AbstractQuery<*>,
        bookmarkRoot: jakarta.persistence.criteria.Root<TableBookmark>,
        postJoin: jakarta.persistence.criteria.Join<TableBookmark, TablePost>,
        userId: UUID,
        folderId: UUID?,
        onlyUncategorized: Boolean,
        search: String?,
        semanticMatches: Map<UUID, Double>,
    ): List<Predicate> {
        val predicates = mutableListOf<Predicate>()

        predicates.add(cb.equal(bookmarkRoot.get<UUID>("userId"), userId))

        // 폴더 필터 — bookmark_folder_items 상관 서브쿼리 (EXISTS/NOT EXISTS). DISTINCT 금지:
        // 이 쿼리는 bookmarks.created_at / PostSearchQuery.relevanceScore 로 정렬하는데 둘 다
        // select 목록(postJoin)에 없어 Postgres가 SELECT DISTINCT ... ORDER BY 를 거부한다.
        // 세미조인은 row를 증식시키지 않으므로 DISTINCT 없이도 중복이 발생하지 않는다.
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

        // Search Filter (Title, Description, Tags, AI 요약) — 토큰 분리 후 OR 매칭 + 의미 매칭 (피드 검색과 동일 로직)
        val searchTokens = PostSearchQuery.tokenize(search)
        if (searchTokens.isNotEmpty()) {
            predicates.add(PostSearchQuery.searchPredicate(cb, postJoin, searchTokens, semanticMatches))
        }

        // Post visibility: isPrivate=false OR post.userId=currentUserId (북마크 소유자)
        val publicPredicate = cb.equal(postJoin.get<Boolean>("isPrivate"), false)
        val ownerPredicate = cb.equal(postJoin.get<UUID>("userId"), userId)
        predicates.add(cb.or(publicPredicate, ownerPredicate))

        return predicates
    }
}
