package com.example.linksphere.domain.post

import com.example.linksphere.global.common.CursorKeyType
import com.example.linksphere.global.common.KeysetSlice
import java.util.UUID

interface PostRepositoryCustom {
    /**
     * 글 목록을 cursor로 이어 읽는다. after(이전 쪽 마지막 행의 키 값) 다음부터 size개를 읽는다. 첫 쪽이면 after는 null이다.
     * withTotal이면 후보 건수를 함께 센다 - 쪽마다 세지 않으려고 호출하는 쪽이 정한다.
     */
    fun findPostsAfter(
        // 카테고리 필터 - null이면 거르지 않고, 빈 집합이면 결과가 없다(이름·slug를 id로 푸는 일은 PostService가 한다)
        categoryIds: Set<Long>?,
        search: String?,
        // filter 파라미터를 쉼표로 나눈 값 가운데 글 테이블만으로 거르는 것(isMyPosts·isPrivate). 모르는 값은 무시한다
        filters: Set<String>,
        // 이 id의 글만(내가 북마크·좋아요한 글) - null이면 거르지 않고, 빈 집합이면 쿼리 없이 빈 결과다(id는 PostService가 interaction에 묻는다)
        postIds: Set<UUID>?,
        // 작성자 필터(닉네임 검색) - null이면 거르지 않고, 빈 집합이면 결과가 없다(닉네임을 id로 푸는 일은 PostService가 member에 묻는다)
        authorIds: Set<UUID>?,
        // 뺄 작성자(봇 제외) - 빈 집합이면 조건을 걸지 않는다
        excludedAuthorIds: Set<UUID>,
        currentUserId: UUID?,
        sort: PostListSort,
        after: List<Comparable<*>>?,
        size: Int,
        // 검색어 임베딩 - null이면 키워드 전용. Gemini 호출 실패/타임아웃 시에도 PostService가 null로 넘겨
        // 검색이 항상 키워드 결과로 폴백하게 한다.
        queryEmbedding: FloatArray? = null,
        withTotal: Boolean = false,
    ): KeysetSlice<TablePost>

    /**
     * candidateIds 가운데 viewerId가 볼 수 있고 search에 걸리는 글을 order의 키 순서로 모두 돌려준다(PostCandidateService.rankVisible).
     * 쪽을 자르지 않는다 - 같은 키 안의 순서를 묻는 쪽이 정하므로 어디서 끊을지 여기서는 알 수 없다. fromKey가 있으면 그 키와
     * 같거나 뒤인 글만 돌려준다. 후보가 비면 DB에 묻지 않는다.
     */
    fun rankVisibleAmong(
        candidateIds: Collection<UUID>,
        viewerId: UUID,
        order: PostRankOrder,
        search: String?,
        fromKey: Comparable<*>?,
        queryEmbedding: FloatArray? = null,
    ): List<RankedPost>
}

// 피드의 정렬과 cursor에 담는 키 구성. 키 순서는 PostRepositoryImpl.findPostsAfter와 맞춘다
enum class PostListSort(val keyTypes: List<CursorKeyType>) {
    LATEST(listOf(CursorKeyType.TIME, CursorKeyType.ID)),
    RELEVANCE(listOf(CursorKeyType.NUMBER, CursorKeyType.TIME, CursorKeyType.ID)),
}
