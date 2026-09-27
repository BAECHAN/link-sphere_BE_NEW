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
 * - 공백 제거본과 기호까지 제거한 본 양쪽을 OR로 매칭한다(예: "NN/g"가 "nng"로도 걸리게) -
 *   기호 제거는 필드 쪽에만 적용하고 검색어 쪽은 그대로 둔다("node.js"가 "nodejs"로는 안
 *   걸리는 한계가 있지만, 결과가 줄어드는 방향이 아니라 늘어나기만 하는 안전한 확장이다)
 * - 제목/설명/태그/AI 요약을 대상으로 OR 매칭(recall 우선), 관련도 점수로 정렬
 */
object PostSearchQuery {

    private const val TITLE_WEIGHT = 3
    private const val TAGS_WEIGHT = 2
    private const val DESCRIPTION_WEIGHT = 1
    private const val AI_SUMMARY_WEIGHT = 1
    private const val TOKEN_MATCH_BONUS = 5
    private const val TITLE_EXACT_BONUS = 100
    private const val TITLE_PREFIX_BONUS = 50

    // 제거할 기호를 명시적으로 나열한다 - `[^[:alnum:]]` 같은 부정 문자 클래스는 쓰지 않는다.
    // DB의 LC_CTYPE에 따라 한글이 "영숫자"로 인식되지 않으면 한글까지 통째로 지워질 수
    // 있어서다(Supabase 로케일 미확인, 안전한 쪽으로 방어). `]`를 목록 맨 앞에, `-`를 맨
    // 뒤에 둬 POSIX bracket expression에서 둘 다 리터럴로 해석되게 한다.
    // internal: Postgres에 보내는 값 그대로를 PostSearchQueryTest에서 Java Regex로도
    // 검증하기 위해 테스트에 노출한다(같은 리터럴을 테스트에 다시 베끼면 나중에 둘이
    // 갈라질 수 있다).
    internal const val STRIP_CHARS = """[]!"#$%&'()*+,./:;<=>?@^_`{|}~·…-]"""

    // 태그는 array_to_string(tags, ',')로 합쳐진 문자열이라 쉼표까지 지우면 태그끼리
    // 이어붙어 오매칭된다(예: "라이프스타일,데이터" → "라이프스타일데이터"). 쉼표만 뺀
    // 같은 목록을 따로 둔다.
    internal const val STRIP_CHARS_KEEP_COMMA = """[]!"#$%&'()*+./:;<=>?@^_`{|}~·…-]"""

    /** 검색어를 공백으로 토큰 분리한다. trim → split(\s+) → 빈 토큰 제거 → lowercase. */
    fun tokenize(search: String?): List<String> = search
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.split(Regex("\\s+"))
        ?.filter { it.isNotEmpty() }
        ?.map { it.lowercase() }
        ?: emptyList()

    /** 토큰 중 하나라도 제목/설명/태그/AI 요약에 매칭되면 포함(OR). count/data 쿼리 공통 WHERE. */
    fun searchPredicate(cb: CriteriaBuilder, from: From<*, TablePost>, tokens: List<String>): Predicate {
        val fields = SearchableFields(cb, from)

        val perToken =
            tokens.map { token ->
                val like = "%$token%"
                cb.or(
                    fields.titleMatches(cb, like),
                    fields.descriptionMatches(cb, like),
                    fields.tagsMatches(cb, like),
                    fields.summaryMatches(cb, like),
                )
            }
        return cb.or(*perToken.toTypedArray())
    }

    /** 필드별 가중치 + 토큰 매칭 보너스 + 제목 완전일치/prefix 보너스 합산. ORDER BY 전용. */
    fun relevanceScore(cb: CriteriaBuilder, from: From<*, TablePost>, tokens: List<String>): Expression<Int> {
        val fields = SearchableFields(cb, from)

        val terms = mutableListOf<Expression<Int>>()
        for (token in tokens) {
            val like = "%$token%"
            terms += caseInt(cb, fields.titleMatches(cb, like), TITLE_WEIGHT)
            terms += caseInt(cb, fields.tagsMatches(cb, like), TAGS_WEIGHT)
            terms += caseInt(cb, fields.descriptionMatches(cb, like), DESCRIPTION_WEIGHT)
            terms += caseInt(cb, fields.summaryMatches(cb, like), AI_SUMMARY_WEIGHT)
            terms +=
                caseInt(
                    cb,
                    cb.or(
                        fields.titleMatches(cb, like),
                        fields.descriptionMatches(cb, like),
                        fields.tagsMatches(cb, like),
                        fields.summaryMatches(cb, like),
                    ),
                    TOKEN_MATCH_BONUS,
                )
        }

        val fullNorm = tokens.joinToString("")
        terms +=
            cb.selectCase<Int>()
                .`when`(
                    cb.or(cb.equal(fields.titleNorm, cb.literal(fullNorm)), cb.equal(fields.titleStripped, cb.literal(fullNorm))),
                    cb.literal(TITLE_EXACT_BONUS),
                )
                .`when`(
                    cb.or(cb.like(fields.titleNorm, "$fullNorm%"), cb.like(fields.titleStripped, "$fullNorm%")),
                    cb.literal(TITLE_PREFIX_BONUS),
                )
                .otherwise(cb.literal(0))

        return terms.fold(cb.literal(0) as Expression<Int>) { acc, term -> cb.sum(acc, term) }
    }

    private fun caseInt(cb: CriteriaBuilder, cond: Predicate, weight: Int): Expression<Int> = cb.selectCase<Int>().`when`(cond, cb.literal(weight)).otherwise(cb.literal(0))

    /** 공백 제거본(norm)과 기호까지 제거한 본(stripped)을 한 번씩만 계산해 재사용하는 묶음. */
    private class SearchableFields(cb: CriteriaBuilder, from: From<*, TablePost>) {
        val titleNorm: Expression<String> = norm(cb, from.get("title"))
        val titleStripped: Expression<String> = stripSymbols(cb, titleNorm)
        private val descNorm: Expression<String> = norm(cb, from.get("description"))
        private val descStripped: Expression<String> = stripSymbols(cb, descNorm)
        private val tagsNorm: Expression<String> = tagsNorm(cb, from)
        private val tagsStripped: Expression<String> = stripSymbolsKeepComma(cb, tagsNorm)
        private val summaryNorm: Expression<String> = norm(cb, from.get("aiSummary"))
        private val summaryStripped: Expression<String> = stripSymbols(cb, summaryNorm)

        fun titleMatches(cb: CriteriaBuilder, like: String): Predicate = fieldMatches(cb, titleNorm, titleStripped, like)

        fun descriptionMatches(cb: CriteriaBuilder, like: String): Predicate = fieldMatches(cb, descNorm, descStripped, like)

        fun tagsMatches(cb: CriteriaBuilder, like: String): Predicate = fieldMatches(cb, tagsNorm, tagsStripped, like)

        fun summaryMatches(cb: CriteriaBuilder, like: String): Predicate = fieldMatches(cb, summaryNorm, summaryStripped, like)

        private fun fieldMatches(cb: CriteriaBuilder, fieldNorm: Expression<String>, fieldStripped: Expression<String>, like: String): Predicate = cb.or(cb.like(fieldNorm, like), cb.like(fieldStripped, like))
    }

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

    private fun stripSymbols(cb: CriteriaBuilder, expr: Expression<String>): Expression<String> = regexpReplace(cb, expr, STRIP_CHARS)

    private fun stripSymbolsKeepComma(cb: CriteriaBuilder, expr: Expression<String>): Expression<String> = regexpReplace(cb, expr, STRIP_CHARS_KEEP_COMMA)

    private fun regexpReplace(cb: CriteriaBuilder, expr: Expression<String>, pattern: String): Expression<String> = cb.function(
        "regexp_replace",
        String::class.java,
        expr,
        cb.literal(pattern),
        cb.literal(""),
        cb.literal("g"),
    )
}
