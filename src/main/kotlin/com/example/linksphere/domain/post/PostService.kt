package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.comment.CommentService
import com.example.linksphere.domain.interaction.BookmarkFolderItemRepository
import com.example.linksphere.domain.interaction.BookmarkFolderRepository
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.member.EmailVerificationGate
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.common.CursorCodec
import com.example.linksphere.global.common.KeysetSlice
import com.example.linksphere.global.common.Paging
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.DuplicatePostException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.PostNotFoundException
import com.example.linksphere.infra.ai.GeminiService
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
    private val bookmarkRepository: BookmarkRepository,
    private val bookmarkFolderItemRepository: BookmarkFolderItemRepository,
    private val bookmarkFolderRepository: BookmarkFolderRepository,
    private val postViewRepository: PostViewRepository,
    private val commentService: CommentService,
    private val postResponseAssembler: PostResponseAssembler,
    private val eventPublisher: ApplicationEventPublisher,
    private val urlMetadataExtractor: UrlMetadataExtractor,
    private val safeUrlValidator: SafeUrlValidator,
    private val geminiService: GeminiService,
    private val memberRepository: MemberRepository,
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
        val member = memberRepository.findById(userId).orElseThrow { IllegalStateException("User not found") }
        EmailVerificationGate.require(member, "create a post")

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
        val categories =
            if (!request.categoryIds.isNullOrEmpty()) {
                categoryService.getCategoriesByIds(request.categoryIds).toMutableSet()
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
                idempotencyKey = idempotencyKey,
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

        return postResponseAssembler.convertToResponse(savedPost, userId)
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
        allowSemanticSearch: Boolean = true,
    ): PostPageResponse {
        val pageable = Paging.pageRequest(page, size)
        val searchTokens = PostSearchQuery.tokenize(search)
        // embedQuery는 실패·타임아웃이면 null을 돌려준다 - 그러면 findPosts가 키워드 전용으로
        // 동작해 검색 자체가 Gemini 장애로 실패하는 일은 없다. allowSemanticSearch=false(호출부
        // 레이트리밋 초과)도 같은 키워드 전용 경로로 강등한다.
        val queryEmbedding =
            search
                ?.takeIf { allowSemanticSearch && searchTokens.isNotEmpty() }
                ?.let { geminiService.embedQuery(PostEmbeddingText.query(it)) }
        val postPage = postRepository.findPosts(category, search, filter, nickname, currentUserId, pageable, queryEmbedding)

        // 검색 결과가 없으면 한/영 자판 미스매칭 보정 후보로 한 번 더 검색한다 (예: spdlqj -> 네이버).
        // 보정 검색은 키워드 전용으로만 한다 - 드문 폴백 경로에 임베딩 호출을 추가하지 않는다.
        if (postPage.totalElements == 0L && !search.isNullOrBlank()) {
            val correctedSearch = HangulKeyboardConverter.convertIfMislayout(search)
            if (correctedSearch != null) {
                val correctedPage =
                    postRepository.findPosts(category, correctedSearch, filter, nickname, currentUserId, pageable)
                if (correctedPage.totalElements > 0L) {
                    logSearch(search = search, total = correctedPage.totalElements, corrected = true, semantic = false)
                    return PostPageResponse.from(
                        correctedPage,
                        postResponseAssembler.buildResponsesFromPosts(
                            correctedPage.content,
                            currentUserId,
                            PostSearchQuery.tokenize(correctedSearch),
                        ),
                        correctedSearch,
                    )
                }
            }
        }

        logSearch(search = search, total = postPage.totalElements, corrected = false, semantic = queryEmbedding != null)
        return PostPageResponse.from(
            postPage,
            postResponseAssembler.buildResponsesFromPosts(postPage.content, currentUserId, searchTokens),
        )
    }

    /**
     * cursor로 이어 읽는 목록(docs/PAGINATION.md). 첫 쪽의 의미 검색·자판 보정 여부를 cursor에 담아 뒤쪽도 같은 기준으로 읽는다.
     * 쪽마다 후보 집합이 바뀌면 점수 비교의 기준이 흔들려 글을 놓치기 때문이다. 다만 의미 검색으로 시작했어도 뒤쪽에서 임베딩을
     * 못 받으면 막지 않고 키워드로 내려가 그대로 유지한다 - Gemini 장애로 검색이 실패하지 않게 한 getAllPosts와 같은 원칙이다.
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
        val searchTokens = PostSearchQuery.tokenize(search)
        val sort = if (searchTokens.isEmpty()) PostListSort.LATEST else PostListSort.RELEVANCE
        val state = cursor?.let { CursorCodec.decode(it, sort.name, sort.keyTypes) }
        val correctedSearch = search?.takeIf { state?.corrected == true }?.let { HangulKeyboardConverter.convertIfMislayout(it) }
        val wantsEmbedding = searchTokens.isNotEmpty() && correctedSearch == null && state?.semantic != false
        val queryEmbedding = search?.takeIf { wantsEmbedding && tryConsumeEmbedding() }?.let { geminiService.embedQuery(PostEmbeddingText.query(it)) }
        val semantic = queryEmbedding != null
        val isFirstSearchPage = state == null && searchTokens.isNotEmpty()

        if (state?.semantic == true && !semantic) {
            logger.warn("[Search] scope=feed semantic degraded to keyword")
        }

        val slice =
            postRepository.findPostsAfter(
                category,
                correctedSearch ?: search,
                filter,
                nickname,
                currentUserId,
                sort,
                state?.keys,
                limit,
                queryEmbedding,
                withTotal = isFirstSearchPage,
            )

        // 쪽마다 건수를 세지 않으므로 첫 쪽이 비었을 때만 보정을 판정한다. 뒤쪽이 비었다고 0건 검색은 아니다.
        val candidate = search?.takeIf { isFirstSearchPage && slice.content.isEmpty() }?.let { HangulKeyboardConverter.convertIfMislayout(it) }
        if (candidate != null) {
            val correctedSlice = postRepository.findPostsAfter(category, candidate, filter, nickname, currentUserId, sort, null, limit, withTotal = true)
            if (correctedSlice.content.isNotEmpty()) {
                logSearch(search = search, total = correctedSlice.total ?: PostSearchQuery.TOTAL_NOT_COUNTED, corrected = true, semantic = false)
                return cursorResponse(correctedSlice, sort, currentUserId, PostSearchQuery.tokenize(candidate), candidate, semantic = false)
            }
        }

        logSearch(search = search, total = slice.total ?: PostSearchQuery.TOTAL_NOT_COUNTED, corrected = correctedSearch != null, semantic = semantic)
        return cursorResponse(
            slice,
            sort,
            currentUserId,
            correctedSearch?.let { PostSearchQuery.tokenize(it) } ?: searchTokens,
            correctedSearch,
            semantic.takeIf { searchTokens.isNotEmpty() },
        )
    }

    private fun cursorResponse(
        slice: KeysetSlice<TablePost>,
        sort: PostListSort,
        currentUserId: UUID?,
        tokens: List<String>,
        correctedSearch: String?,
        semantic: Boolean?,
    ): PostPageResponse = PostPageResponse(
        content = postResponseAssembler.buildResponsesFromPosts(slice.content, currentUserId, tokens),
        nextCursor = slice.nextKeys?.let { CursorCodec.encode(sort.name, it, semantic, corrected = true.takeIf { correctedSearch != null }) },
        correctedSearch = correctedSearch,
    )

    /**
     * 검색어별 결과 건수를 로그로 남긴다 - 0건 검색어 파악, 의미 검색 임계값 튜닝 근거로 쓴다.
     * 검색어 원문은 CloudWatch에 남지만 사용자 식별 정보는 포함하지 않고, 줄바꿈·따옴표는
     * Logs Insights 파싱이 깨지지 않도록 한 줄로 정리한다.
     */
    private fun logSearch(search: String?, total: Long, corrected: Boolean, semantic: Boolean) {
        if (search.isNullOrBlank()) return

        logger.info(
            "[Search] scope=feed tokens={} total={} corrected={} semantic={} q=\"{}\"",
            PostSearchQuery.tokenize(search).size,
            total,
            corrected,
            semantic,
            search.replace(Regex("[\\r\\n\"]"), " ").take(100),
        )
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
        post.categories.clear()
        if (!request.categoryIds.isNullOrEmpty()) {
            post.categories.addAll(categoryService.getCategoriesByIds(request.categoryIds))
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
        // comments.post_id FK가 ON DELETE CASCADE라 댓글 row는 DB에서 자동 삭제되지만,
        // 댓글에 딸린 스토리지 이미지는 정리되지 않으므로 게시글이 지워지기 전에 먼저 정리한다.
        commentService.deleteImagesForPost(id)
        postRepository.delete(post)
    }

    /**
     * 작성·수정 폼의 링크 미리보기. 등록과 같은 게이트(이메일 인증·URL 검증)를 먼저 통과해야 한다 -
     * 미리보기에서 "이 주소를 찾을 수 없어요"를 미리 보여주려는 것이기도 하다. 크롤링 결과는
     * LinkPreviewService가 10분 캐시해 등록 때 재사용한다(캐시 저장이 있어 쓰기 트랜잭션).
     */
    @Transactional
    fun previewLink(userId: UUID, rawUrl: String): LinkPreviewResponse {
        val member = memberRepository.findById(userId).orElseThrow { IllegalStateException("User not found") }
        EmailVerificationGate.require(member, "preview a link")

        val url = rawUrl.trim()
        validateUrl(url)
        val metadata = linkPreviewService.get(url)
        return LinkPreviewResponse(url = url, title = metadata.title, description = metadata.description, ogImage = metadata.ogImage)
    }

    private fun validateUrl(url: String) = safeUrlValidator.validate(url)
}
