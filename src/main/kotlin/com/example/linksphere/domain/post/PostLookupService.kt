package com.example.linksphere.domain.post

import com.example.linksphere.global.exception.PostNotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 다른 패키지가 글을 id로 묻는 창구(docs/plans/2026-11-16-be-structure-stage2.md PR-8). 댓글·좋아요·북마크는 `TablePost`와
 * `PostRepository`를 모르고, "이 글을 볼 수 있는가"·"작성자가 누구인가"·"제목이 무엇인가"·"링크 카드에 무엇을 보여 줄까"만
 * 여기에 묻는다.
 *
 * PostService에 두지 않는 이유: 이 클래스는 PostRepository만 참조해 누구에게 주입돼도 빈 순환이 생길 수 없다. PostService는
 * 응답을 조립하느라 다른 패키지에 묻는 것이 많다 - 지금은 interaction의 저장소를 직접 쓰고, 그것을 interaction이 구현하는
 * 포트로 바꾸면(같은 계획 PR-9) 그 구현이 다시 PostService를 주입받을 때 서로 물린다. 묻는 창구를 처음부터 떼어 둔다
 * (PostResponseAssembler를 따로 둔 것과 같은 이유, docs/BE-ARCHITECTURE.md §7-8).
 */
@Service
@Transactional(readOnly = true)
class PostLookupService(private val postRepository: PostRepository) {

    /**
     * 글이 있고 viewerId가 볼 수 있는지 확인한 뒤 작성자 id를 돌려준다. 없거나 볼 수 없으면 둘 다 PostNotFoundException이다 -
     * 남의 비공개 글은 존재 자체를 숨긴다(PostVisibility). viewerId가 null이면 비로그인이다.
     */
    fun requireVisible(postId: UUID, viewerId: UUID?): UUID {
        val post = postRepository.findByIdOrNull(postId) ?: throw PostNotFoundException(postId)
        PostVisibility.assertVisible(post, viewerId, postId)

        return post.userId
    }

    /** 여러 글을 한 번에 읽어 requireVisible과 같은 기준으로 확인한다. 넘겨받은 순서대로 보고 처음 걸린 글의 id로 예외를 낸다. */
    fun requireAllVisible(postIds: Collection<UUID>, viewerId: UUID?) {
        val postsById = postRepository.findAllById(postIds).associateBy { it.id }

        postIds.forEach { postId ->
            val post = postsById[postId] ?: throw PostNotFoundException(postId)
            PostVisibility.assertVisible(post, viewerId, postId)
        }
    }

    /** 공개 여부를 따지지 않고 작성자만 묻는다(댓글 알림의 받는 사람). 글이 없으면 null이다. */
    fun findAuthorId(postId: UUID): UUID? = postRepository.findByIdOrNull(postId)?.userId

    /** viewerId가 볼 수 있는 글의 제목만 돌려준다. 없거나 볼 수 없는 글은 결과에 없다. 빈 목록이면 DB를 보지 않는다. */
    fun findVisibleTitles(postIds: Collection<UUID>, viewerId: UUID): Map<UUID, String> {
        if (postIds.isEmpty()) {
            return emptyMap()
        }

        return postRepository.findVisibleTitles(postIds, viewerId).associate { it.id to it.title }
    }

    /**
     * viewerId가 볼 수 있는 글의 링크 카드 정보(제목·설명·썸네일)만 돌려준다. 없거나 볼 수 없는 글은 결과에 없다. viewerId가 null이면
     * 비로그인이라 공개 글만 나간다. 빈 목록이면 DB를 보지 않는다.
     */
    fun findVisiblePreviews(postIds: Collection<UUID>, viewerId: UUID?): Map<UUID, PostLinkPreview> {
        if (postIds.isEmpty()) {
            return emptyMap()
        }

        val rows =
            if (viewerId == null) {
                postRepository.findPublicPreviews(postIds)
            } else {
                postRepository.findVisiblePreviews(postIds, viewerId)
            }

        return rows.associate { it.id to PostLinkPreview(title = it.title, description = it.description, ogImage = it.ogImage) }
    }
}
