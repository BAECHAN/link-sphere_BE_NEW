package com.example.linksphere.domain.post

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
}
