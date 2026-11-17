package com.example.linksphere.domain.post

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 후보 글을 줄 세울 첫 정렬 키. NONE이면 키 없이 걸러 내기만 한다(순서는 묻는 쪽이 자기 값으로 정한다). */
enum class PostRankOrder {
    NONE,

    // 제목 앞 200자, 오름차순
    TITLE,

    // 보는 사람이 그 글을 마지막으로 본 시각, 최근순. 한 번도 안 본 글은 맨 뒤다
    VIEWED,

    // 검색 점수, 높은 순
    RELEVANCE,
}

/** 후보 가운데 남은 글 하나와 그 첫 정렬 키 값. order가 NONE이면 key는 null이다. */
data class RankedPost(val postId: UUID, val key: Comparable<*>?)

/**
 * 다른 패키지가 "내가 가진 글 id 가운데"로 글 목록을 묻는 창구(docs/plans/2026-11-16-be-structure-stage2.md PR-9c).
 * 북마크함(interaction)은 자기 테이블에서 후보 id를 읽어 넘기고, 공개 여부·검색·글 쪽 정렬 키(제목·열람 시각·검색 점수)와
 * 응답 조립은 여기에 묻는다 - `TablePost`·`TablePostView`를 직접 조인하지 않는다.
 *
 * PostLookupService에 두지 않는 이유: 응답을 조립하려면 PostResponseAssembler가 필요한데, PostLookupService는 PostRepository만
 * 참조해 누구에게 주입돼도 빈 순환이 생기지 않게 둔 클래스다. 이 클래스가 참조하는 조립기도 post·interaction의 Service를
 * 참조하지 않아 interaction의 Service가 주입받아도 순환이 없다(docs/BE-ARCHITECTURE.md §7-8).
 */
@Service
@Transactional(readOnly = true)
class PostCandidateService(
    private val postRepository: PostRepository,
    private val postResponseAssembler: PostResponseAssembler,
) {

    private val logger = LoggerFactory.getLogger(PostCandidateService::class.java)

    /** postIds 가운데 viewerId가 볼 수 있는 글의 id. 없거나 볼 수 없는 글은 빠진다. 빈 목록이면 DB를 보지 않는다. */
    fun findVisibleIds(postIds: Collection<UUID>, viewerId: UUID): Set<UUID> {
        if (postIds.isEmpty()) {
            return emptySet()
        }

        return postRepository.findVisibleIds(postIds, viewerId).toSet()
    }

    /**
     * 후보 가운데 viewerId가 볼 수 있고 search에 걸리는 글을 order의 키 순서로 돌려준다. 순서는 DB가 정한다 - 같은 키 안의
     * 순서는 정해져 있지 않으니 묻는 쪽이 자기 값(북마크 시각)으로 맞춘다. fromKey가 있으면 그 키와 같거나 뒤인 글만 온다
     * (키가 같은 글은 모두 온다 - 그 안에서 어디까지 읽었는지는 묻는 쪽만 안다).
     */
    fun rankVisible(
        candidateIds: Collection<UUID>,
        viewerId: UUID,
        order: PostRankOrder,
        search: String?,
        fromKey: Comparable<*>?,
        queryEmbedding: FloatArray?,
    ): List<RankedPost> {
        // 후보는 IN 목록으로 간다. 드라이버는 한 문장의 바인드 값이 65,535개를 넘으면 예외를 던진다(PostService.POST_ID_FILTER_WARN_SIZE와 같은 이유)
        if (candidateIds.size > CANDIDATE_WARN_SIZE) {
            logger.warn("[PostCandidate] 후보 목록이 큼 - size={}, order={}", candidateIds.size, order)
        }

        return postRepository.rankVisibleAmong(candidateIds, viewerId, order, search, fromKey, queryEmbedding)
    }

    /**
     * postIds 순서대로 응답을 조립한다. 그 사이 지워진 글은 빠진다. 공개 여부는 다시 보지 않는다 - findVisibleIds·rankVisible로
     * 거른 id를 넘긴다. searchTokens는 PostResponseAssembler.buildResponsesFromPosts와 같은 뜻이다.
     */
    fun buildResponses(postIds: List<UUID>, viewerId: UUID, searchTokens: List<String> = emptyList()): List<PostResponse> {
        if (postIds.isEmpty()) {
            return emptyList()
        }

        val postsById = postRepository.findAllById(postIds).associateBy { it.id }

        return postResponseAssembler.buildResponsesFromPosts(postIds.mapNotNull { postsById[it] }, viewerId, searchTokens)
    }

    private companion object {
        const val CANDIDATE_WARN_SIZE = 10_000
    }
}
