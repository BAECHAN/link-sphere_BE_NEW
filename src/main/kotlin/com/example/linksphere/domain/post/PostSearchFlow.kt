package com.example.linksphere.domain.post

import com.example.linksphere.global.common.CursorState
import com.example.linksphere.global.common.KeysetSlice
import com.example.linksphere.infra.ai.GeminiService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 검색 흐름을 지난 한 쪽. searchTokens·correctedSearch·semantic은 응답과 다음 cursor에 그대로 싣는다. */
data class PostSearchResult<T>(
    val slice: KeysetSlice<T>,
    // 실제로 검색에 쓴 말의 토큰(자판 보정으로 재검색했으면 보정어의 토큰)
    val searchTokens: List<String>,
    // 자판 보정으로 재검색했을 때만 채운다
    val correctedSearch: String?,
    // 의미 검색으로 읽었는가. 검색이 아니면 null
    val semantic: Boolean?,
)

/**
 * 목록 검색의 공통 흐름 - 검색어 임베딩, 첫 쪽이 비었을 때의 자판 보정 재검색, 검색 로그. 피드(PostService)와 북마크함
 * (interaction의 BookmarkFolderService)이 같은 흐름을 쓴다. 북마크함은 이 흐름을 복사해 갖고 있었다(docs/plans/2026-11-16-be-structure-stage2.md PR-9c).
 *
 * 첫 쪽의 의미 검색·자판 보정 여부는 cursor에 담겨 돌아오고(state), 뒤쪽도 같은 기준으로 읽는다. 무엇을 어떻게 읽을지는
 * find가 정한다 - 이 클래스는 저장소를 모른다.
 */
@Component
class PostSearchFlow(private val geminiService: GeminiService) {

    private val logger = LoggerFactory.getLogger(PostSearchFlow::class.java)

    /**
     * scope는 로그에 남는 이름이다(feed·bookmark). tryConsumeEmbedding은 임베딩을 실제로 부를 때만 불린다 - 호출 한도를 쓰는 쪽(피드)만 넘긴다.
     * find(검색어, after, 임베딩, 건수를 셀지)는 한 쪽을 읽는다. 첫 검색 쪽이 비면 보정어로 한 번 더 불린다.
     */
    fun <T> run(
        scope: String,
        search: String?,
        state: CursorState?,
        tryConsumeEmbedding: () -> Boolean = { true },
        find: (search: String?, after: List<Comparable<*>>?, queryEmbedding: FloatArray?, withTotal: Boolean) -> KeysetSlice<T>,
    ): PostSearchResult<T> {
        val searchTokens = PostSearchQuery.tokenize(search)
        val correctedSearch = search?.takeIf { state?.corrected == true }?.let { HangulKeyboardConverter.convertIfMislayout(it) }
        val wantsEmbedding = searchTokens.isNotEmpty() && correctedSearch == null && state?.semantic != false
        val queryEmbedding = search?.takeIf { wantsEmbedding && tryConsumeEmbedding() }?.let { geminiService.embedQuery(PostEmbeddingText.query(it)) }
        val semantic = queryEmbedding != null
        val isFirstSearchPage = state == null && searchTokens.isNotEmpty()

        if (state?.semantic == true && !semantic) {
            logger.warn("[Search] scope={} semantic degraded to keyword", scope)
        }

        val slice = find(correctedSearch ?: search, state?.keys, queryEmbedding, isFirstSearchPage)

        // 쪽마다 건수를 세지 않으므로 첫 쪽이 비었을 때만 보정을 판정한다. 뒤쪽이 비었다고 0건 검색은 아니다.
        val candidate = search?.takeIf { isFirstSearchPage && slice.content.isEmpty() }?.let { HangulKeyboardConverter.convertIfMislayout(it) }

        if (candidate != null) {
            val correctedSlice = find(candidate, null, null, true)

            if (correctedSlice.content.isNotEmpty()) {
                logSearch(scope, search, total = correctedSlice.total ?: PostSearchQuery.TOTAL_NOT_COUNTED, corrected = true, semantic = false)

                return PostSearchResult(correctedSlice, PostSearchQuery.tokenize(candidate), candidate, semantic = false)
            }
        }

        logSearch(scope, search, total = slice.total ?: PostSearchQuery.TOTAL_NOT_COUNTED, corrected = correctedSearch != null, semantic = semantic)

        return PostSearchResult(
            slice,
            correctedSearch?.let { PostSearchQuery.tokenize(it) } ?: searchTokens,
            correctedSearch,
            semantic.takeIf { searchTokens.isNotEmpty() },
        )
    }

    /**
     * 검색어별 결과 건수를 로그로 남긴다 - 0건 검색어 파악, 의미 검색 임계값 튜닝 근거로 쓴다.
     * 검색어 원문은 CloudWatch에 남지만 사용자 식별 정보는 포함하지 않고, 줄바꿈·따옴표는
     * Logs Insights 파싱이 깨지지 않도록 한 줄로 정리한다.
     */
    private fun logSearch(scope: String, search: String?, total: Long, corrected: Boolean, semantic: Boolean) {
        if (search.isNullOrBlank()) {
            return
        }

        logger.info(
            "[Search] scope={} tokens={} total={} corrected={} semantic={} q=\"{}\"",
            scope,
            PostSearchQuery.tokenize(search).size,
            total,
            corrected,
            semantic,
            search.replace(Regex("[\\r\\n\"]"), " ").take(100),
        )
    }
}
