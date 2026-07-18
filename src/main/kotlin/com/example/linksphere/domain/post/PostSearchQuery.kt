package com.example.linksphere.domain.post

import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.From
import jakarta.persistence.criteria.Predicate

/**
 * 게시글 검색용 토큰 파싱 + 매칭 predicate + 관련도 점수 로직.
 *
 * 피드(Root<TablePost>)와 북마크(Join<TableBookmark, TablePost>) 양쪽에서 공유한다.
 * - 내용은 공백 제거 후 매칭(lower(replace(field, ' ', ''))), 검색어는 공백으로 토큰 분리
 * - OR 매칭(recall 우선), 관련도 점수로 정렬
 */
object PostSearchQuery {

    private const val TITLE_WEIGHT = 3
    private const val TAGS_WEIGHT = 2
    private const val DESCRIPTION_WEIGHT = 1
    private const val TOKEN_MATCH_BONUS = 5
    private const val TITLE_EXACT_BONUS = 100
    private const val TITLE_PREFIX_BONUS = 50

    /** 검색어를 공백으로 토큰 분리한다. trim → split(\s+) → 빈 토큰 제거 → lowercase. */
    fun tokenize(search: String?): List<String> = search
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.split(Regex("\\s+"))
        ?.filter { it.isNotEmpty() }
        ?.map { it.lowercase() }
        ?: emptyList()

    /** 토큰 중 하나라도 제목/설명/태그에 매칭되면 포함(OR). count/data 쿼리 공통 WHERE. */
    fun searchPredicate(cb: CriteriaBuilder, from: From<*, TablePost>, tokens: List<String>): Predicate {
        val titleNorm = titleNorm(cb, from)
        val descNorm = descNorm(cb, from)
        val tagsNorm = tagsNorm(cb, from)

        val perToken =
            tokens.map { token ->
                val like = "%$token%"
                cb.or(cb.like(titleNorm, like), cb.like(descNorm, like), cb.like(tagsNorm, like))
            }
        return cb.or(*perToken.toTypedArray())
    }

    /** 필드별 가중치 + 토큰 매칭 보너스 + 제목 완전일치/prefix 보너스 합산. ORDER BY 전용. */
    fun relevanceScore(cb: CriteriaBuilder, from: From<*, TablePost>, tokens: List<String>): Expression<Int> {
        val titleNorm = titleNorm(cb, from)
        val descNorm = descNorm(cb, from)
        val tagsNorm = tagsNorm(cb, from)

        val terms = mutableListOf<Expression<Int>>()
        for (token in tokens) {
            val like = "%$token%"
            terms += caseInt(cb, cb.like(titleNorm, like), TITLE_WEIGHT)
            terms += caseInt(cb, cb.like(tagsNorm, like), TAGS_WEIGHT)
            terms += caseInt(cb, cb.like(descNorm, like), DESCRIPTION_WEIGHT)
            terms +=
                caseInt(
                    cb,
                    cb.or(cb.like(titleNorm, like), cb.like(descNorm, like), cb.like(tagsNorm, like)),
                    TOKEN_MATCH_BONUS,
                )
        }

        val fullNorm = tokens.joinToString("")
        terms +=
            cb.selectCase<Int>()
                .`when`(cb.equal(titleNorm, cb.literal(fullNorm)), cb.literal(TITLE_EXACT_BONUS))
                .`when`(cb.like(titleNorm, "$fullNorm%"), cb.literal(TITLE_PREFIX_BONUS))
                .otherwise(cb.literal(0))

        return terms.fold(cb.literal(0) as Expression<Int>) { acc, term -> cb.sum(acc, term) }
    }

    private fun caseInt(cb: CriteriaBuilder, cond: Predicate, weight: Int): Expression<Int> = cb.selectCase<Int>().`when`(cond, cb.literal(weight)).otherwise(cb.literal(0))

    private fun titleNorm(cb: CriteriaBuilder, from: From<*, TablePost>): Expression<String> = norm(cb, from.get("title"))

    private fun descNorm(cb: CriteriaBuilder, from: From<*, TablePost>): Expression<String> = norm(cb, from.get("description"))

    private fun tagsNorm(cb: CriteriaBuilder, from: From<*, TablePost>): Expression<String> = cb.lower(
        cb.function(
            "replace",
            String::class.java,
            cb.function("array_to_string", String::class.java, from.get<Any>("tags"), cb.literal(",")),
            cb.literal(" "),
            cb.literal(""),
        ),
    )

    private fun norm(cb: CriteriaBuilder, path: Expression<String>): Expression<String> = cb.lower(cb.function("replace", String::class.java, path, cb.literal(" "), cb.literal("")))
}
