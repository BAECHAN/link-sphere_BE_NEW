package com.example.linksphere.domain.post

import com.example.linksphere.global.common.CursorKeyType
import com.example.linksphere.global.common.KeysetSlice
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

interface PostRepositoryCustom {
    fun findPosts(
        category: String?,
        search: String?,
        filter: String?,
        nickname: String?,
        currentUserId: UUID?,
        pageable: Pageable,
        // 검색어 임베딩 - null이면 키워드 전용(기존 동작 그대로). Gemini 호출 실패/타임아웃
        // 시에도 PostService가 null로 넘겨 검색이 항상 키워드 결과로 폴백하게 한다.
        queryEmbedding: FloatArray? = null,
    ): Page<TablePost>

    /**
     * findPosts의 cursor 판. after(이전 쪽 마지막 행의 키 값) 다음부터 size개를 읽는다. 첫 쪽이면 after는 null이다.
     * 필터는 findPosts와 같다. withTotal이면 후보 건수를 함께 센다 - 쪽마다 세지 않으려고 호출하는 쪽이 정한다.
     */
    fun findPostsAfter(
        category: String?,
        search: String?,
        filter: String?,
        nickname: String?,
        currentUserId: UUID?,
        sort: PostListSort,
        after: List<Comparable<*>>?,
        size: Int,
        queryEmbedding: FloatArray? = null,
        withTotal: Boolean = false,
    ): KeysetSlice<TablePost>
}

// 피드의 정렬과 cursor에 담는 키 구성. 키 순서는 PostRepositoryImpl.findPostsAfter와 맞춘다
enum class PostListSort(val keyTypes: List<CursorKeyType>) {
    LATEST(listOf(CursorKeyType.TIME, CursorKeyType.ID)),
    RELEVANCE(listOf(CursorKeyType.NUMBER, CursorKeyType.TIME, CursorKeyType.ID)),
}
