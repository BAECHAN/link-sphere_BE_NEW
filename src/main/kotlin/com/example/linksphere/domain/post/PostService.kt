package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.linkpreview.LinkPreviewService
import com.example.linksphere.domain.linkpreview.SafeUrlValidator
import com.example.linksphere.domain.linkpreview.UrlMetadata
import com.example.linksphere.domain.linkpreview.UrlMetadataExtractor
import com.example.linksphere.domain.linkpreview.WeakTitleDetector
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.common.CursorCodec
import com.example.linksphere.global.common.Paging
import com.example.linksphere.global.exception.DuplicatePostException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.PostNotFoundException
import com.example.linksphere.global.ratelimit.RateLimitService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

@Service
@Transactional(readOnly = true)
class PostService(
    private val postRepository: PostRepository,
    private val categoryService: CategoryService,
    private val postInteractionPort: PostInteractionPort,
    private val postViewRepository: PostViewRepository,
    private val postResponseAssembler: PostResponseAssembler,
    private val eventPublisher: ApplicationEventPublisher,
    private val urlMetadataExtractor: UrlMetadataExtractor,
    private val safeUrlValidator: SafeUrlValidator,
    private val postSearchFlow: PostSearchFlow,
    private val memberService: MemberService,
    private val linkPreviewService: LinkPreviewService,
    private val advisoryLock: AdvisoryLock,
    private val rateLimitService: RateLimitService,
) {

    private val logger = LoggerFactory.getLogger(PostService::class.java)

    companion object {
        // 같은 회원이 같은 URL을 다른 키로 다시 등록하면 막는 창. 표준 값이 없어 운영 간격을 보고 정했다
        // (2026-11-04 사용자 결정, docs/plans/2026-11-04-idempotent-create.md "판단이 필요했던 항목").
        private val DUPLICATE_URL_WINDOW = Duration.ofHours(1)

        // 같은 조회자가 같은 글을 다시 열어도 이 창에서는 조회수를 한 번만 올린다. RateLimitService의 고정 윈도라
        // "마지막으로 센 때부터 24시간"이 아니라 UTC 하루(한국 시각 오전 9시 경계)에 한 번이다
        // (2026-11-11 사용자 결정, docs/plans/2026-11-11-post-view-dedupe.md "판단이 필요했던 항목").
        private val VIEW_COUNT_WINDOW = Duration.ofHours(24)
        private const val VIEW_COUNT_LIMIT = 1

        // 목록의 filter 파라미터 값 - 봇 계정이 쓴 글을 뺀다. 글 테이블만으로 거르는 값(isMyPosts·isPrivate)은 PostRepositoryImpl이 푼다
        private const val EXCLUDE_BOTS_FILTER = "excludeBots"

        // 목록의 filter 파라미터 값 - 내가 북마크·좋아요한 글만. id 집합을 interaction에 물어 IN으로 건다
        private const val BOOKMARKED_FILTER = "isBookmarked"
        private const val LIKED_FILTER = "isLiked"

        // IN 목록이 이 수를 넘으면 경고 로그를 남긴다(결과는 줄이지 않는다). 드라이버는 한 문장의 바인드 값이 65,535개를 넘으면
        // 예외를 던지므로(pgjdbc 42.7.11 PgPreparedStatement.maximumNumberOfParameters, simple 모드만 예외) 그 전에 알아채려는
        // 값이다. 운영의 회원당 최대는 북마크 95·좋아요 9다(2026-11-17, 읽기 전용 계정 조회)
        private const val POST_ID_FILTER_WARN_SIZE = 10_000

        // 검색 로그에 남는 이름
        private const val SEARCH_SCOPE = "feed"
    }

    fun findByIdempotencyKey(userId: UUID, idempotencyKey: UUID): PostResponse? = postRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
        ?.let { postResponseAssembler.convertToResponse(it, userId) }

    @Transactional
    fun createPost(
        userId: UUID,
        request: PostCreateRequest,
        fallbackContent: String? = null,
        idempotencyKey: UUID? = null,
    ): PostResponse {
        // 크롤링 등 비싼 작업을 하기 전에 먼저 확인한다 - 읽기·좋아요·북마크는 막지 않고
        // 글쓰기만 막는다(docs/plans/2026-09-28-auth-hardening.md "확정된 결정들" 참고).
        memberService.requireEmailVerified(userId, "create a post")

        val url = request.url.trim()
        validateUrl(url)

        // 키가 없는 경로는 봇(FeedItemProcessor)뿐이고, 봇은 같은 글을 feed_items로 따로 막는다.
        if (idempotencyKey != null) {
            advisoryLock.lockForTransaction("post-create:$userId:$url")

            // 잠금을 기다리는 동안 같은 키의 요청이 먼저 끝났으면 그 글이 곧 이 요청의 결과다 - 아래 시간 창에 걸려 409가 되면 안 된다.
            postRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)?.let {
                return postResponseAssembler.convertToResponse(it, userId)
            }

            if (postRepository.existsByUserIdAndUrlAndCreatedAtAfter(userId, url, LocalDateTime.now().minus(DUPLICATE_URL_WINDOW))) {
                throw DuplicatePostException(url)
            }
        }

        // 작성 중 미리보기(10분 이내)가 있으면 그 결과를 그대로 쓴다 - 사용자가 본 미리보기와 저장되는
        // 글이 같아지고 크롤링을 다시 하지 않는다. 없으면(미리보기 전 제출·만료·봇) 기존처럼 크롤링한다.
        val metadata = linkPreviewService.findFresh(url) ?: urlMetadataExtractor.extract(url)
        // 크롤링이 실패하면 pageContent가 null이라 AI 분석이 통째로 스킵된다(아래 aiStatus=NONE).
        // fallbackContent는 어떤 @RequestBody DTO에도 없는 파라미터라 외부 사용자가 채울 수 없고,
        // 봇 경로(FeedItemProcessor)가 RSS 본문을 미리 크롤링해 넘겨줄 때만 대체된다.
        val pageContent = metadata.pageContent ?: fallbackContent?.takeIf { it.isNotBlank() }

        val title = if (!request.title.isNullOrBlank()) request.title else metadata.title
        val categoryIds =
            if (!request.categoryIds.isNullOrEmpty()) {
                categoryService.filterExistingIds(request.categoryIds).toMutableSet()
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
                categoryIds = categoryIds,
                ogImage = metadata.ogImage,
                aiStatus = if (pageContent != null) AiStatus.PENDING else AiStatus.NONE,
                isPrivate = request.isPrivate,
                idempotencyKey = idempotencyKey,
            )
        val savedPost = postRepository.save(newPost)

        val shouldBookmark = request.bookmark || !request.folderIds.isNullOrEmpty()
        if (shouldBookmark) {
            // 북마크 저장은 interaction이 맡는다(PostInteractionPort). insert가 네이티브 쿼리라 posts 행이 먼저 DB에 있어야 해 flush한다
            postRepository.flush()
            postInteractionPort.bookmarkNewPost(userId, savedPost.id!!, request.folderIds.orEmpty().distinct())
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

        return postResponseAssembler.convertToResponse(savedPost, userId)
    }

    /**
     * cursor로 이어 읽는 목록(docs/PAGINATION.md). 첫 쪽의 의미 검색·자판 보정 여부를 cursor에 담아 뒤쪽도 같은 기준으로 읽는다.
     * 쪽마다 후보 집합이 바뀌면 점수 비교의 기준이 흔들려 글을 놓치기 때문이다. 다만 의미 검색으로 시작했어도 뒤쪽에서 임베딩을
     * 못 받으면 막지 않고 키워드로 내려가 그대로 유지한다 - Gemini 장애로 검색이 실패하지 않게 한다.
     *
     * tryConsumeEmbedding은 임베딩을 실제로 부를 때만 불러, 의미 검색을 안 하는 쪽이 IP당 호출 한도를 쓰지 않게 한다.
     */
    fun getPostsByCursor(
        category: String?,
        search: String?,
        filter: String?,
        nickname: String?,
        cursor: String?,
        size: Int,
        currentUserId: UUID?,
        tryConsumeEmbedding: () -> Boolean = { true },
    ): PostPageResponse {
        val limit = Paging.coerceSize(size)
        val sort = if (PostSearchQuery.tokenize(search).isEmpty()) PostListSort.LATEST else PostListSort.RELEVANCE
        val state = cursor?.let { CursorCodec.decode(it, sort.name, sort.keyTypes) }
        val filters = filter?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        // 필터의 id 조회는 처음 읽을 때(find 안) 한 번 한다 - 검색어 임베딩(PostSearchFlow)이 이 조회들보다 먼저 불리던 순서를 지킨다
        val categoryIds by lazy { resolveCategoryFilter(category) }
        // 닉네임·봇 필터는 member에 id를 물어 저장소에는 id 집합만 넘긴다 - 글 쿼리가 회원 테이블을 직접 읽지 않는다
        val authorIds by lazy { nickname?.takeIf { it.isNotBlank() }?.let { memberService.findIdsByNicknameContaining(it) } }
        val excludedAuthorIds by lazy { if (EXCLUDE_BOTS_FILTER in filters) memberService.findBotIds() else emptySet() }
        val postIds by lazy { resolveInteractionFilter(filters, currentUserId) }
        // 임베딩·자판 보정·검색 로그는 북마크함과 같은 흐름을 쓴다(PostSearchFlow)
        val result =
            postSearchFlow.run(SEARCH_SCOPE, search, state, tryConsumeEmbedding) { query, after, queryEmbedding, withTotal ->
                postRepository.findPostsAfter(categoryIds, query, filters, postIds, authorIds, excludedAuthorIds, currentUserId, sort, after, limit, queryEmbedding, withTotal)
            }

        return PostPageResponse(
            content = postResponseAssembler.buildResponsesFromPosts(result.slice.content, currentUserId, result.searchTokens),
            nextCursor = result.slice.nextKeys?.let { CursorCodec.encode(sort.name, it, result.semantic, corrected = true.takeIf { result.correctedSearch != null }) },
            correctedSearch = result.correctedSearch,
        )
    }

    /**
     * category 파라미터(쉼표로 여러 개, 이름이나 slug)를 카테고리 id로 푼다. 파라미터가 없으면 null(거르지 않음)이고,
     * 맞는 카테고리가 하나도 없으면 빈 집합(결과 없음)이다.
     */
    private fun resolveCategoryFilter(category: String?): Set<Long>? {
        val namesOrSlugs = category?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        return if (namesOrSlugs.isEmpty()) null else categoryService.resolveIds(namesOrSlugs)
    }

    /**
     * filter의 isBookmarked·isLiked를 글 id 집합으로 푼다 - 글 쿼리가 북마크·좋아요 테이블을 직접 읽지 않고 interaction에 묻는다.
     * 둘 다 없거나 비로그인이면 null(거르지 않음)이고, 둘 다 있으면 둘 다 한 글만 남는다.
     */
    private fun resolveInteractionFilter(filters: Set<String>, viewerId: UUID?): Set<UUID>? {
        if (viewerId == null) {
            return null
        }

        val idSets =
            listOfNotNull(
                if (BOOKMARKED_FILTER in filters) postInteractionPort.findBookmarkedPostIds(viewerId) else null,
                if (LIKED_FILTER in filters) postInteractionPort.findLikedPostIds(viewerId) else null,
            )

        if (idSets.isEmpty()) {
            return null
        }

        val postIds = idSets.reduce { acc, ids -> acc intersect ids }

        if (postIds.size > POST_ID_FILTER_WARN_SIZE) {
            logger.warn("[PostFilter] id 목록이 큼 - size={}, filters={}", postIds.size, filters)
        }

        return postIds
    }

    fun getPostById(id: UUID, currentUserId: UUID?): PostResponse {
        val post = postRepository.findById(id).orElseThrow { PostNotFoundException(id) }
        // 목록/북마크 조회에는 있는 가시성 검증이 상세 조회에는 빠져 있었다.
        // 존재 여부를 알려주지 않도록 403이 아닌 404로 던진다.
        PostVisibility.assertVisible(post, currentUserId, id)
        return postResponseAssembler.convertToResponse(post, currentUserId)
    }

    /**
     * 상세 화면이 뜰 때 FE가 부르는 조회 기록. 읽기(getPostById)와 나눈 이유는 카드 hover 미리 받기·탭 복귀 재조회·수정 화면도
     * 같은 GET을 보내 조회로 세지던 것이다(docs/plans/2026-11-05-post-view-record.md).
     *
     * 같은 조회자는 VIEW_COUNT_WINDOW에 한 번만 센다 - 새로고침·재진입마다 오르던 것을 막는다
     * (docs/plans/2026-11-11-post-view-dedupe.md). 최근 열람 시각은 셌는지와 상관없이 매번 갱신한다. 세는 규칙을 FE가
     * 따라 하지 않도록 판단 뒤의 조회수를 돌려준다. 판단(tryConsume)은 별도 트랜잭션으로 먼저 커밋돼, 그 뒤에 이
     * 메서드가 실패하면 그 조회자의 이번 창 조회는 빠진다.
     */
    @Transactional
    fun recordView(id: UUID, currentUserId: UUID?, clientIp: String?): PostViewRecordResponse {
        val post = postRepository.findById(id).orElseThrow { PostNotFoundException(id) }
        PostVisibility.assertVisible(post, currentUserId, id)
        if (rateLimitService.tryConsume(viewBucketKey(id, currentUserId, clientIp), VIEW_COUNT_LIMIT, VIEW_COUNT_WINDOW)) {
            postRepository.incrementViewCount(id)
        }
        currentUserId?.let { postViewRepository.upsertView(it, id) }
        return PostViewRecordResponse(postRepository.findViewCountById(id))
    }

    // 조회자를 로그인하면 회원, 아니면 IP로 가린다. IP도 모르면(CloudFront 헤더 없음) null을 돌려줘 tryConsume이 매번 통과시킨다
    private fun viewBucketKey(postId: UUID, userId: UUID?, clientIp: String?): String? = when {
        userId != null -> "post-view:$postId:member:$userId"
        clientIp != null -> "post-view:$postId:ip:$clientIp"
        else -> null
    }

    @Transactional
    fun updatePost(id: UUID, userId: UUID, request: PostUpdateRequest): PostResponse {
        val post = postRepository.findById(id).orElseThrow { PostNotFoundException(id) }
        if (post.userId != userId) throw ForbiddenException("You are not the owner of this post")

        post.isPrivate = request.isPrivate
        post.categoryIds.clear()
        if (!request.categoryIds.isNullOrEmpty()) {
            post.categoryIds.addAll(categoryService.filterExistingIds(request.categoryIds))
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
        val metadata = recrawlUrl?.let { linkPreviewService.findFresh(it) ?: urlMetadataExtractor.extract(it) }

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
            // 임베딩도 aiSummary와 같은 이유로 리셋한다 - embedding은 insertable/updatable=false라
            // save(post)로는 안 지워지므로 네이티브 UPDATE로 직접 null을 쓴다. 재수집으로 새
            // PostCreatedEvent가 발행되면(아래) AI 잡이 새 임베딩으로 다시 채운다.
            postRepository.updateEmbedding(id, null)
        } else if (metadata != null) {
            // 제목만 비운 재수집은 "제목을 다시 가져와 달라"지 "이 글을 초기화해 달라"가 아니다.
            // 제목이 빈약한 페이지는 본문·썸네일도 못 긁히는 같은 껍데기 페이지라, 여기서 전면
            // 덮어쓰기를 하면 제목 하나 고치려다 설명·태그·AI 요약을 함께 잃는다. 비어 있는
            // 칸만 채우는 순수 폴백으로 둔다(PostAIService의 폴백 원칙과 동일).
            if (post.description.isNullOrBlank()) post.description = metadata.description
            if (post.ogImage.isNullOrBlank()) post.ogImage = metadata.ogImage
        }

        // 재수집한 김에 AI도 다시 돌린다. aiSummary를 미리 지우지 않는 이유는 PostAIService가
        // 요약을 순수 폴백으로 쓰기 때문이다(PostAIService.kt) - 재분석이 실패해도 기존
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

        return postResponseAssembler.convertToResponse(savedPost, userId)
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
        return postResponseAssembler.convertToResponse(postRepository.save(post), userId)
    }

    @Transactional
    fun deletePost(id: UUID, userId: UUID) {
        val post = postRepository.findById(id).orElseThrow { PostNotFoundException(id) }
        if (post.userId != userId) throw ForbiddenException("You are not the owner of this post")
        // comments.post_id FK가 ON DELETE CASCADE라 댓글 row는 DB에서 자동 삭제되지만, 댓글에 딸린 스토리지 이미지는
        // 정리되지 않는다. 글을 지우기 전에 알려 comment가 이미지 URL을 읽어 두게 한다(PostLifecycleEvents.kt) -
        // 듣는 쪽은 이 트랜잭션 안에서 바로 실행되고, 실패하면 삭제도 되돌려진다.
        eventPublisher.publishEvent(PostDeletingEvent(id))
        postRepository.delete(post)
    }

    /**
     * 작성·수정 폼의 링크 미리보기. 등록과 같은 게이트(이메일 인증·URL 검증)를 먼저 통과해야 한다 -
     * 미리보기에서 "이 주소를 찾을 수 없어요"를 미리 보여주려는 것이기도 하다. 크롤링 결과는
     * LinkPreviewService가 10분 캐시해 등록 때 재사용한다(캐시 저장이 있어 쓰기 트랜잭션).
     */
    @Transactional
    fun previewLink(userId: UUID, rawUrl: String): LinkPreviewResponse {
        memberService.requireEmailVerified(userId, "preview a link")

        val url = rawUrl.trim()
        validateUrl(url)
        val metadata = linkPreviewService.get(url)
        return LinkPreviewResponse(url = url, title = metadata.title, description = metadata.description, ogImage = metadata.ogImage)
    }

    private fun validateUrl(url: String) = safeUrlValidator.validate(url)
}
