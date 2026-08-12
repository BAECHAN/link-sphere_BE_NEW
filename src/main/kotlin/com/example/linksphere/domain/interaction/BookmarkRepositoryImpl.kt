package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.PostSearchQuery
import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.domain.post.TablePostView
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
    ): Page<TablePost> {
        val cb = entityManager.criteriaBuilder

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
                ).toTypedArray(),
            )

        // sort — 검색어가 있고 기본(latest) 정렬이면 관련도순, 그 외엔 사용자 선택 유지
        // 모든 뷰(전체/미분류/폴더)에서 "최신순"은 bookmarks.created_at 기준 — 폴더별 소속 시각이 아니다.
        val searchTokens = PostSearchQuery.tokenize(search)
        val orders =
            if (searchTokens.isNotEmpty() && sort == "latest") {
                listOf(
                    cb.desc(PostSearchQuery.relevanceScore(cb, postJoin, searchTokens)),
                    cb.desc(bookmarkRoot.get<Any>("createdAt")),
                )
            } else {
                when (sort) {
                    "oldest" -> listOf(cb.asc(bookmarkRoot.get<Any>("createdAt")))
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
                            // 않는다 — 2차 정렬로 안정시킨다("latest"와 동일 기준: 최근 북마크순)
                            cb.desc(bookmarkRoot.get<Any>("createdAt")),
                        )
                    }
                    else -> listOf(cb.desc(bookmarkRoot.get<Any>("createdAt"))) // "latest" default
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

    private fun buildPredicates(
        cb: jakarta.persistence.criteria.CriteriaBuilder,
        query: jakarta.persistence.criteria.AbstractQuery<*>,
        bookmarkRoot: jakarta.persistence.criteria.Root<TableBookmark>,
        postJoin: jakarta.persistence.criteria.Join<TableBookmark, TablePost>,
        userId: UUID,
        folderId: UUID?,
        onlyUncategorized: Boolean,
        search: String?,
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

        // Search Filter (Title, Description, or Tags) — 토큰 분리 후 OR 매칭 (피드 검색과 동일 로직)
        val searchTokens = PostSearchQuery.tokenize(search)
        if (searchTokens.isNotEmpty()) {
            predicates.add(PostSearchQuery.searchPredicate(cb, postJoin, searchTokens))
        }

        // Post visibility: isPrivate=false OR post.userId=currentUserId (북마크 소유자)
        val publicPredicate = cb.equal(postJoin.get<Boolean>("isPrivate"), false)
        val ownerPredicate = cb.equal(postJoin.get<UUID>("userId"), userId)
        predicates.add(cb.or(publicPredicate, ownerPredicate))

        return predicates
    }
}
