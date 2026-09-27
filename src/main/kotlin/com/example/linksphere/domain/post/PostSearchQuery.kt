package com.example.linksphere.domain.post

import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.From
import jakarta.persistence.criteria.Predicate
import java.util.UUID

/**
 * 게시글 검색용 토큰 파싱 + 매칭 predicate + 관련도 점수 로직.
 *
 * 피드(Root<TablePost>)와 북마크(Join<TableBookmark, TablePost>) 양쪽에서 공유한다.
 * - 내용은 공백 제거 후 매칭(lower(replace(field, ' ', ''))), 검색어는 공백으로 토큰 분리
 * - 공백 제거본과 기호까지 제거한 본 양쪽을 OR로 매칭한다(예: "NN/g"가 "nng"로도 걸리게) -
 *   기호 제거는 필드 쪽에만 적용하고 검색어 쪽은 그대로 둔다("node.js"가 "nodejs"로는 안
 *   걸리는 한계가 있지만, 결과가 줄어드는 방향이 아니라 늘어나기만 하는 안전한 확장이다)
 * - 제목/설명/태그/AI 요약을 대상으로 OR 매칭(recall 우선), 관련도 점수로 정렬
 * - semanticMatches가 주어지면 키워드 OR 의미(코사인 거리) 매칭으로 확장한다(2026-09-28,
 *   docs/plans/2026-09-27-search-quality.md 2e). 임계값 0.30은 실제 임베딩 216건 +
 *   한/영 질의 쌍으로 실측해 정했다 - 진짜 관련 결과는 0.24~0.31, "향수"·"폰케이스" 같은
 *   무관한 글과 자판 오타성 노이즈 질의는 전부 0.31 이상에서 시작했다.
 *
 * resolveSemanticMatches()가 네이티브 쿼리로 후보 id 목록을 미리 뽑아온다 - hibernate-vector가
 * Criteria API에서 cosine_distance()를 vector 컬럼과 함께 호출하는 걸 지원하지 않아서다
 * ("Parameter 1 of function 'cosine_distance()' requires a vector type, but argument is
 * of type 'float[]'", 2026-09-28 로컬에서 실제로 재현·확인). 이 쿼리 자체(<=> 연산자를
 * 직접 쓰는 네이티브 SQL)는 2d 오프라인 평가 때 이미 검증된 형태다.
 */
object PostSearchQuery {

    private const val TITLE_WEIGHT = 3
    private const val TAGS_WEIGHT = 2
    private const val DESCRIPTION_WEIGHT = 1
    private const val AI_SUMMARY_WEIGHT = 1
    private const val TOKEN_MATCH_BONUS = 5
    private const val TITLE_EXACT_BONUS = 100
    private const val TITLE_PREFIX_BONUS = 50

    // TITLE_EXACT_BONUS(100)보다 한참 아래로 둬 정확한 제목 일치가 항상 의미 매칭을 이긴다.
    private const val SEMANTIC_WEIGHT = 20.0
    private const val MAX_COSINE_DISTANCE = 0.30

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

    /**
     * queryEmbedding으로 임계값(MAX_COSINE_DISTANCE) 안의 게시글 id → 거리 맵을 네이티브
     * 쿼리로 미리 뽑는다. count/data 양쪽 쿼리가 이 결과를 공유해야 총 건수와 실제 반환
     * 건수가 항상 일치한다 - findPosts/findBookmarkedPosts에서 한 번만 호출한다.
     */
    fun resolveSemanticMatches(entityManager: EntityManager, queryEmbedding: FloatArray?): Map<UUID, Double> {
        if (queryEmbedding == null) return emptyMap()

        val literal = PostEmbeddingText.toVectorLiteral(queryEmbedding)

        @Suppress("UNCHECKED_CAST")
        val rows =
            entityManager.createNativeQuery(
                """
                SELECT id, embedding <=> CAST(:qv AS vector) AS distance
                FROM posts
                WHERE embedding IS NOT NULL AND embedding <=> CAST(:qv AS vector) < :threshold
                """.trimIndent(),
            )
                .setParameter("qv", literal)
                .setParameter("threshold", MAX_COSINE_DISTANCE)
                .resultList as List<Array<Any>>

        return rows.associate { (it[0] as UUID) to (it[1] as Number).toDouble() }
    }

    /**
     * 토큰 중 하나라도 제목/설명/태그/AI 요약에 매칭되거나(키워드), semanticMatches에 이
     * 글의 id가 있으면(의미) 포함. count/data 쿼리 공통 WHERE.
     */
    fun searchPredicate(cb: CriteriaBuilder, from: From<*, TablePost>, tokens: List<String>, semanticMatches: Map<UUID, Double> = emptyMap()): Predicate {
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
        val keyword = cb.or(*perToken.toTypedArray())
        return if (semanticMatches.isEmpty()) keyword else cb.or(keyword, from.get<UUID>("id").`in`(semanticMatches.keys))
    }

    /**
     * 필드별 가중치 + 토큰 매칭 보너스 + 제목 완전일치/prefix 보너스 + 의미 유사도
     * ((1-거리)*SEMANTIC_WEIGHT, semanticMatches에 없으면 0) 합산. ORDER BY 전용.
     */
    fun relevanceScore(cb: CriteriaBuilder, from: From<*, TablePost>, tokens: List<String>, semanticMatches: Map<UUID, Double> = emptyMap()): Expression<Number> {
        val fields = SearchableFields(cb, from)

        val terms = mutableListOf<Expression<out Number>>()
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

        if (semanticMatches.isNotEmpty()) {
            // id별로 (1-거리)*SEMANTIC_WEIGHT를 CASE로 나열한다 - 이 임계값 안 후보 수는
            // 이 레포 규모(공개 글 수백 건)에서 많아야 수십 건이라 CASE 분기 수가 문제되지 않는다.
            var caseExpr = cb.selectCase<Double>()
            for ((id, distance) in semanticMatches) {
                caseExpr = caseExpr.`when`(cb.equal(from.get<UUID>("id"), id), cb.literal((1.0 - distance) * SEMANTIC_WEIGHT))
            }
            terms += caseExpr.otherwise(0.0)
        }

        return terms.fold(cb.literal(0) as Expression<Number>) { acc, term -> cb.sum(acc, term) }
    }

    private fun caseInt(cb: CriteriaBuilder, cond: Predicate, weight: Int): Expression<Int> = cb.selectCase<Int>().`when`(cond, cb.literal(weight)).otherwise(cb.literal(0))

    /**
     * 검색 결과 배지("의미로 찾았어요")용 - 이 글이 키워드로도 걸리는지 Kotlin에서 다시
     * 판정한다. SQL WHERE와는 별개 구현이지만 STRIP_CHARS/STRIP_CHARS_KEEP_COMMA 리터럴을
     * 그대로 재사용해 두 구현이 갈라지지 않게 한다. 페이지당 10건 수준이라 비용은
     * 무시할 만하다.
     */
    fun matchesKeywordLiterally(post: TablePost, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false

        val titleNorm = normalize(post.title)
        val descNorm = normalize(post.description.orEmpty())
        val tagsNorm = normalize(post.tags?.joinToString(",").orEmpty())
        val summaryNorm = normalize(post.aiSummary.orEmpty())
        val candidates =
            listOf(
                titleNorm,
                titleNorm.replace(Regex(STRIP_CHARS), ""),
                descNorm,
                descNorm.replace(Regex(STRIP_CHARS), ""),
                tagsNorm,
                tagsNorm.replace(Regex(STRIP_CHARS_KEEP_COMMA), ""),
                summaryNorm,
                summaryNorm.replace(Regex(STRIP_CHARS), ""),
            )

        return tokens.any { token -> candidates.any { it.contains(token) } }
    }

    private fun normalize(value: String): String = value.lowercase().replace(" ", "")

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
