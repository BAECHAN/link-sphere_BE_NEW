package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryRepository
import com.example.linksphere.domain.category.CategoryResponse
import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.comment.CommentService
import com.example.linksphere.domain.interaction.BookmarkFolderItemRepository
import com.example.linksphere.domain.interaction.BookmarkFolderRepository
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.interaction.PostReactionRepository
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.PostNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class PostService(
    private val postRepository: PostRepository,
    private val categoryRepository: CategoryRepository,
    private val memberRepository: MemberRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val bookmarkFolderItemRepository: BookmarkFolderItemRepository,
    private val bookmarkFolderRepository: BookmarkFolderRepository,
    private val postViewRepository: PostViewRepository,
    private val postReactionRepository: PostReactionRepository,
    private val commentRepository: CommentRepository,
    private val commentService: CommentService,
    private val eventPublisher: ApplicationEventPublisher,
    private val urlMetadataExtractor: UrlMetadataExtractor,
    private val safeUrlValidator: SafeUrlValidator,
) {

    private val logger = LoggerFactory.getLogger(PostService::class.java)

    @Transactional
    fun createPost(userId: UUID, request: PostCreateRequest, fallbackContent: String? = null): PostResponse {
        val url = request.url.trim()
        validateUrl(url)
        val metadata = urlMetadataExtractor.extract(url)
        // 크롤링이 실패하면 pageContent가 null이라 AI 분석이 통째로 스킵된다(아래 aiStatus=NONE).
        // fallbackContent는 어떤 @RequestBody DTO에도 없는 파라미터라 외부 사용자가 채울 수 없고,
        // 봇 경로(FeedItemProcessor)가 RSS 본문을 미리 크롤링해 넘겨줄 때만 대체된다.
        val pageContent = metadata.pageContent ?: fallbackContent?.takeIf { it.isNotBlank() }

        val title = if (!request.title.isNullOrBlank()) request.title else metadata.title
        val categories =
            if (!request.categoryIds.isNullOrEmpty()) {
                categoryRepository.findAllByIdIn(request.categoryIds).toMutableSet()
            } else {
                mutableSetOf()
            }

        val newPost =
            TablePost(
                userId = userId,
                url = url,
                title = title,
                description = metadata.description,
                tags = metadata.tags.toMutableList(),
                categories = categories,
                ogImage = metadata.ogImage,
                aiStatus = if (pageContent != null) AiStatus.PENDING else AiStatus.NONE,
                isPrivate = request.isPrivate,
            )
        val savedPost = postRepository.save(newPost)

        val shouldBookmark = request.bookmark || !request.folderIds.isNullOrEmpty()
        if (shouldBookmark) {
            saveBookmarkWithFolders(userId, savedPost.id!!, request.folderIds.orEmpty().distinct())
        }

        if (pageContent != null) {
            logger.info("[AI Async] PostCreatedEvent 발행 - postId: ${savedPost.id}")
            eventPublisher.publishEvent(
                PostCreatedEvent(
                    postId = savedPost.id!!,
                    userId = userId,
                    title = title,
                    description = metadata.description,
                    content = pageContent,
                    existingTags = metadata.tags,
                ),
            )
        }

        return convertToResponse(savedPost, userId)
    }

    /**
     * 등록과 동시에 북마크 생성. `InteractionService.addBookmarkFolder`와 동일한 검증·insert 순서를 따른다.
     * insert가 네이티브 쿼리라 posts 행이 먼저 DB에 있어야 하므로 flush 후 실행한다.
     */
    private fun saveBookmarkWithFolders(userId: UUID, postId: UUID, folderIds: List<UUID>) {
        postRepository.flush()

        if (folderIds.isNotEmpty()) {
            val folders = bookmarkFolderRepository.findAllById(folderIds)
            val foundIds = folders.map { it.id }.toSet()
            folderIds.firstOrNull { it !in foundIds }?.let { throw BookmarkFolderNotFoundException(it) }
            folders.firstOrNull { it.userId != userId }
                ?.let { throw ForbiddenException("Cannot add bookmark to another user's folder") }
        }

        bookmarkRepository.insertIgnoreConflict(userId, postId)
        folderIds.forEach { folderId ->
            bookmarkFolderItemRepository.insertIgnoreConflict(userId, postId, folderId)
        }
    }

    fun getAllPosts(
        category: String?,
        search: String?,
        filter: String?,
        nickname: String?,
        page: Int,
        size: Int,
        currentUserId: UUID?,
    ): PostPageResponse {
        val pageable = PageRequest.of(page, size)
        val postPage = postRepository.findPosts(category, search, filter, nickname, currentUserId, pageable)

        // 검색 결과가 없으면 한/영 자판 미스매칭 보정 후보로 한 번 더 검색한다 (예: spdlqj -> 네이버)
        if (postPage.totalElements == 0L && !search.isNullOrBlank()) {
            val correctedSearch = HangulKeyboardConverter.convertIfMislayout(search)
            if (correctedSearch != null) {
                val correctedPage =
                    postRepository.findPosts(category, correctedSearch, filter, nickname, currentUserId, pageable)
                if (correctedPage.totalElements > 0L) {
                    return PostPageResponse.from(
                        correctedPage,
                        buildResponsesFromPosts(correctedPage.content, currentUserId),
                        correctedSearch,
                    )
                }
            }
        }

        return PostPageResponse.from(postPage, buildResponsesFromPosts(postPage.content, currentUserId))
    }

    /**
     * Post 리스트를 PostResponse 리스트로 변환하면서 author/likes/bookmarks/comments를 batch fetch.
     * 다른 도메인(예: BookmarkFolderService)에서 페이지 변환 시 재사용한다.
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

    @Transactional
    fun getPostById(id: UUID, currentUserId: UUID?): PostResponse {
        val post = postRepository.findById(id).orElseThrow { PostNotFoundException(id) }
        // 목록/북마크 조회에는 있는 가시성 검증이 상세 조회에는 빠져 있었다.
        // 존재 여부를 알려주지 않도록 403이 아닌 404로 던진다.
        if (post.isPrivate && post.userId != currentUserId) throw PostNotFoundException(id)
        postRepository.incrementViewCount(id)
        currentUserId?.let { postViewRepository.upsertView(it, id) }
        return convertToResponse(post, currentUserId)
    }

    @Transactional
    fun updatePost(id: UUID, userId: UUID, request: PostUpdateRequest): PostResponse {
        val post = postRepository.findById(id).orElseThrow { PostNotFoundException(id) }
        if (post.userId != userId) throw ForbiddenException("You are not the owner of this post")

        post.isPrivate = request.isPrivate
        post.categories.clear()
        if (!request.categoryIds.isNullOrEmpty()) {
            post.categories.addAll(categoryRepository.findAllByIdIn(request.categoryIds))
        }

        // 재수집 트리거는 둘이다.
        //  (1) URL 변경 - 기존 메타데이터·AI 요약이 옛 링크 기준이라 통째로 무의미해진다.
        //  (2) 제목 비움 - 수정 폼 placeholder("비워두면 자동으로 가져와요")가 사용자에게 한
        //      약속이다. URL이 그대로여도 크롤링을 다시 돌려야 그 약속을 지킬 수 있다.
        //      (예전에는 URL 변경만 트리거라, 제목만 비우면 조용히 무시됐다.)
        val newUrl = request.url?.trim()?.takeIf { it != post.url }
        val titleCleared = request.title.isNullOrBlank()

        // 검증은 URL이 바뀔 때만 한다. 기존 URL은 등록 시점에 이미 통과한 값이고, 그 사이
        // 사설 IP로 바뀌었더라도 safeConnect가 홉마다 재검증해 extract 안에서 걸러진다 -
        // 여기서 던지면 "제목만 비운 수정"이 400으로 실패해 사용자가 손쓸 방법이 없어진다.
        if (newUrl != null) validateUrl(newUrl)

        val recrawlUrl = newUrl ?: post.url.takeIf { titleCleared }
        val metadata = recrawlUrl?.let { urlMetadataExtractor.extract(it) }

        // 제목 우선순위: 사용자가 직접 쓴 제목 > 재수집 제목 > 기존 제목.
        // 재수집 제목이 빈약하면 채택하지 않는다 - "- YouTube" 같은 껍데기 제목이 멀쩡한 기존
        // 제목을 덮는 것을 막는다. 사슬의 마지막이 항상 non-blank인 기존 제목이므로 빈 제목이
        // DB에 저장될 경로는 없다(posts.title은 NOT NULL, FE postSchema는 min(1)).
        val recrawledTitle = usableTitle(metadata, recrawlUrl)
        if (recrawlUrl != null && recrawledTitle == null) {
            logger.info("[Crawling] 재수집 제목이 빈약해 기존 제목 유지 - postId: $id, title: ${metadata?.title}")
        }
        post.title = request.title?.trim()?.takeIf { it.isNotEmpty() } ?: recrawledTitle ?: post.title

        if (newUrl != null && metadata != null) {
            // URL이 바뀌면 기존 메타데이터·AI 요약은 옛 링크 기준이라 통째로 무의미하다 - 전부 덮는다.
            post.url = newUrl
            post.description = metadata.description
            post.tags = metadata.tags.toMutableList()
            post.ogImage = metadata.ogImage
            post.aiSummary = null
        } else if (metadata != null) {
            // 제목만 비운 재수집은 "제목을 다시 가져와 달라"지 "이 글을 초기화해 달라"가 아니다.
            // 제목이 빈약한 페이지는 본문·썸네일도 못 긁히는 같은 껍데기 페이지라, 여기서 전면
            // 덮어쓰기를 하면 제목 하나 고치려다 설명·태그·AI 요약을 함께 잃는다. 비어 있는
            // 칸만 채우는 순수 폴백으로 둔다(PostAIService의 폴백 원칙과 동일).
            if (post.description.isNullOrBlank()) post.description = metadata.description
            if (post.ogImage.isNullOrBlank()) post.ogImage = metadata.ogImage
        }

        // 재수집한 김에 AI도 다시 돌린다. aiSummary를 미리 지우지 않는 이유는 PostAIService가
        // 요약을 순수 폴백으로 쓰기 때문이다(PostAiService.kt) - 재분석이 실패해도 기존
        // 요약이 남는다. URL 변경 경로는 위에서 이미 aiSummary를 리셋했다.
        val pageContent = metadata?.pageContent
        when {
            pageContent != null -> post.aiStatus = AiStatus.PENDING
            // 본문을 못 건진 새 링크는 NONE으로 되돌린다. 제목만 비운 경우엔 기존 상태를
            // 그대로 둔다 - COMPLETED를 NONE으로 강등시키면 백필 러너의 대상 판정이 흔들린다.
            newUrl != null -> post.aiStatus = AiStatus.NONE
        }

        val savedPost = postRepository.save(post)

        if (pageContent != null) {
            logger.info("[AI Async] 재수집으로 PostCreatedEvent 발행 - postId: ${savedPost.id}")
            eventPublisher.publishEvent(
                PostCreatedEvent(
                    postId = savedPost.id!!,
                    userId = userId,
                    title = post.title, // metadata.title이 아니라 실제 저장된 제목
                    description = post.description, // 제목 비움 경로에선 기존 설명이 맞다
                    content = pageContent,
                    existingTags = post.tags.orEmpty(), // metadata.tags(=호스트 하나)를 넘기면 태그 손실
                ),
            )
        }

        return convertToResponse(savedPost, userId)
    }

    /** 재수집한 제목은 빈약하지 않을 때만 채택한다 - PostAIService와 같은 판정을 쓴다. */
    private fun usableTitle(metadata: UrlMetadata?, url: String?): String? {
        if (metadata == null || url == null || WeakTitleDetector.isWeak(metadata.title, url)) return null
        return metadata.title
    }

    @Transactional
    fun updatePostVisibility(id: UUID, userId: UUID, request: PostVisibilityUpdateRequest): PostResponse {
        val post = postRepository.findById(id).orElseThrow { PostNotFoundException(id) }
        if (post.userId != userId) throw ForbiddenException("You are not the owner of this post")

        post.isPrivate = request.isPrivate
        return convertToResponse(postRepository.save(post), userId)
    }

    @Transactional
    fun deletePost(id: UUID, userId: UUID) {
        val post = postRepository.findById(id).orElseThrow { PostNotFoundException(id) }
        if (post.userId != userId) throw ForbiddenException("You are not the owner of this post")
        // comments.post_id FK가 ON DELETE CASCADE라 댓글 row는 DB에서 자동 삭제되지만,
        // 댓글에 딸린 스토리지 이미지는 정리되지 않으므로 게시글이 지워지기 전에 먼저 정리한다.
        commentService.deleteImagesForPost(id)
        postRepository.delete(post)
    }

    private fun validateUrl(url: String) = safeUrlValidator.validate(url)

    private fun convertToResponse(post: TablePost, currentUserId: UUID?): PostResponse {
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
        userId = post.userId,
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
