package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryResponse
import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.interaction.BookmarkFolderItemRepository
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.interaction.PostReactionRepository
import com.example.linksphere.domain.member.MemberRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * TablePost를 PostResponse로 조립한다 - 작성자·좋아요·북마크·댓글수를 여러 도메인의
 * Repository에서 모아 붙이는 읽기 전용 조립기다.
 *
 * PostService와 BookmarkFolderService 둘 다 이 조립 로직이 필요한데, 원래는 PostService가
 * 소유하고 BookmarkFolderService가 PostService를 주입받아 썼다. 그런데 BookmarkFolderService가
 * PostService를 참조하는 상태에서 PostService가 반대로 BookmarkFolderService(또는 그 소관
 * Repository들)를 서비스 경유로 쓰게 바꾸면 순환 참조가 생긴다. 이 클래스는 어느 도메인
 * Service도 참조하지 않는 순수 Repository 조립기라, 두 서비스가 각자 이 클래스를 주입받아도
 * 순환이 생기지 않는다.
 *
 * 트랜잭션은 스스로 열지 않는다 - 항상 이미 트랜잭션이 열려 있는 PostService/BookmarkFolderService의
 * 메서드 안에서 호출되는 것을 전제로 한다(Spring은 같은 스레드의 트랜잭션 컨텍스트를 공유하므로
 * 이 클래스에 @Transactional이 없어도 호출부의 트랜잭션 안에서 그대로 실행된다).
 */
@Service
class PostResponseAssembler(
    private val memberRepository: MemberRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val bookmarkFolderItemRepository: BookmarkFolderItemRepository,
    private val postReactionRepository: PostReactionRepository,
    private val commentRepository: CommentRepository,
) {

    /**
     * Post 리스트를 PostResponse 리스트로 변환하면서 author/likes/bookmarks/comments를 batch fetch.
     */
    fun buildResponsesFromPosts(posts: List<TablePost>, currentUserId: UUID?): List<PostResponse> {
        if (posts.isEmpty()) return emptyList()

        val postIds = posts.mapNotNull { it.id }

        val authorMap =
            memberRepository.findAllById(posts.map { it.userId }.distinct())
                .associate { m ->
                    val id = m.id!!
                    id to UserSummary(id, m.nickname, m.image)
                }

        val allBookmarks = bookmarkRepository.findAllByPostIdIn(postIds)
        val bookmarkCountMap = allBookmarks.groupingBy { it.postId }.eachCount()
        val myBookmarks =
            if (currentUserId != null) {
                bookmarkRepository.findAllByUserIdAndPostIdIn(currentUserId, postIds)
            } else {
                emptyList()
            }
        val bookmarkedPostIds = myBookmarks.map { it.postId }.toSet()
        val folderIdsByPost: Map<UUID, List<UUID>> =
            if (currentUserId != null) {
                bookmarkFolderItemRepository.findAllByUserIdAndPostIdIn(currentUserId, postIds)
                    .groupBy({ it.postId }, { it.folderId })
            } else {
                emptyMap()
            }

        val allReactions = postReactionRepository.findAllByPostIdIn(postIds)
        val reactionCountMap = allReactions.groupingBy { it.postId }.eachCount()
        val reactedPostIds =
            if (currentUserId != null) {
                postReactionRepository
                    .findAllByUserIdAndPostIdIn(currentUserId, postIds)
                    .map { it.postId }
                    .toSet()
            } else {
                emptySet()
            }

        val commentCountMap =
            commentRepository.countByPostIdIn(postIds)
                .associate { it.postId to it.count.toInt() }

        return posts.map { post ->
            val postId = post.id ?: throw IllegalStateException("Post ID cannot be null")
            val author =
                authorMap[post.userId]
                    ?: throw IllegalArgumentException("Member not found: ${post.userId}")
            buildPostResponse(
                post = post,
                postId = postId,
                author = author,
                likeCount = reactionCountMap[postId] ?: 0,
                isLiked = postId in reactedPostIds,
                bookmarkCount = bookmarkCountMap[postId] ?: 0,
                isBookmarked = postId in bookmarkedPostIds,
                bookmarkFolderIds = folderIdsByPost[postId] ?: emptyList(),
                commentCount = commentCountMap[postId] ?: 0,
            )
        }
    }

    /** 단건 조회/등록/수정 응답 조립 - 배치가 필요 없는 단일 게시글 경로. */
    fun convertToResponse(post: TablePost, currentUserId: UUID?): PostResponse {
        val postId = post.id ?: throw IllegalStateException("Post ID cannot be null")

        val dbAuthor =
            memberRepository.findById(post.userId).orElseThrow {
                IllegalArgumentException("Member not found with id: ${post.userId}")
            }
        val author =
            UserSummary(
                id = dbAuthor.id ?: throw IllegalStateException("User ID cannot be null"),
                nickname = dbAuthor.nickname,
                image = dbAuthor.image,
            )

        val isBookmarked = currentUserId?.let { bookmarkRepository.existsByUserIdAndPostId(it, postId) } ?: false
        // isBookmarked 가 true 인 경우는 currentUserId != null 인 경로(위 let)를 통해서만 나올 수 있으므로
        // 컴파일러가 이 분기 안에서 currentUserId 를 non-null 로 스마트캐스트한다.
        val bookmarkFolderIds =
            if (isBookmarked) {
                bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(currentUserId, postId)
            } else {
                emptyList()
            }
        return buildPostResponse(
            post = post,
            postId = postId,
            author = author,
            likeCount = postReactionRepository.countByPostId(postId).toInt(),
            isLiked =
            currentUserId?.let {
                postReactionRepository.existsByUserIdAndPostId(it, postId)
            } ?: false,
            bookmarkCount = bookmarkRepository.countByPostId(postId).toInt(),
            isBookmarked = isBookmarked,
            bookmarkFolderIds = bookmarkFolderIds,
            commentCount = commentRepository.countByPostId(postId).toInt(),
        )
    }

    private fun buildPostResponse(
        post: TablePost,
        postId: UUID,
        author: UserSummary,
        likeCount: Int,
        isLiked: Boolean,
        bookmarkCount: Int,
        isBookmarked: Boolean,
        bookmarkFolderIds: List<UUID> = emptyList(),
        commentCount: Int,
    ): PostResponse = PostResponse(
        id = postId,
        url = post.url,
        title = post.title,
        description = post.description,
        tags = post.tags,
        categories = post.categories.map { CategoryResponse.from(it) }.sortedBy { it.id },
        ogImage = post.ogImage,
        aiSummary = post.aiSummary,
        createdAt = post.createdAt,
        aiStatus = post.aiStatus,
        isPrivate = post.isPrivate,
        stats =
        PostStats(
            viewCount = post.viewCount ?: 0,
            likeCount = likeCount,
            commentCount = commentCount,
            bookmarkCount = bookmarkCount,
        ),
        userInteractions = PostUserInteractions(
            isLiked = isLiked,
            isBookmarked = isBookmarked,
            bookmarkFolderIds = bookmarkFolderIds,
        ),
        author = author,
    )
}
