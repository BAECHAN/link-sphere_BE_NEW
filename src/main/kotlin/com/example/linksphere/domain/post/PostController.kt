package com.example.linksphere.domain.post

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.ClientIpResolver
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.common.getUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.time.Duration
import java.util.UUID

@Tag(name = "게시글", description = "게시글 등록·조회·수정·삭제")
@RestController
@RequestMapping("/post")
class PostController(
    private val postService: PostService,
    private val rateLimitService: RateLimitService,
) {

    // 한도는 컨트롤러(사용자 요청 경로)에서만 건다 - 봇(FeedItemProcessor)은 PostService를 직접
    // 호출하므로 영향받지 않는다. 값의 근거는 docs/TRAFFIC-MANAGEMENT.md "운영 파라미터" 참고.
    companion object {
        // 글 등록은 요청마다 동기 크롤링(최대 수십 초)과 AI 처리가 붙는 가장 비싼 쓰기다.
        private val CREATE_MEMBER_WINDOW = Duration.ofHours(1)
        private const val CREATE_MEMBER_LIMIT = 20

        // 비로그인도 쓰는 공개 검색이 요청마다 Gemini 임베딩을 부른다 - 초과해도 막지 않고
        // 키워드 검색으로 강등한다. 공유 IP(NAT)를 고려해 넉넉하게 잡는다.
        private val SEARCH_EMBED_IP_WINDOW = Duration.ofMinutes(10)
        private const val SEARCH_EMBED_IP_LIMIT = 60
    }

    @Operation(
        summary = "게시글 등록",
        description = "URL 을 크롤링해 메타데이터를 추출하고 AI 요약을 비동기로 채운다. 10분 안에 같은 URL을 " +
            "GET /link-preview 로 미리 본 적이 있으면 크롤링 대신 그 결과를 쓴다. " +
            "HTTP 상태는 200 이고 본문 status 필드만 201 이다. " +
            "실패: 400 INVALID_URL · 400 URL_UNRESOLVABLE(도메인 없음) · 400 URL_NOT_ALLOWED(내부망) · " +
            "403 EMAIL_NOT_VERIFIED · 404 FOLDER_NOT_FOUND · 403 FORBIDDEN(남의 폴더) · " +
            "429 RATE_LIMIT_EXCEEDED(회원당 시간당 등록 한도 초과)",
    )
    @PostMapping
    fun createPost(
        @RequestBody request: PostCreateRequest,
        authentication: Authentication,
    ): ApiResponse<PostResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        rateLimitService.consume("post-create:member:$userId", CREATE_MEMBER_LIMIT, CREATE_MEMBER_WINDOW)
        val post = postService.createPost(userId, request)
        return ApiResponse(HttpStatus.CREATED.value(), "Post created", post)
    }

    @Operation(
        summary = "게시글 목록 조회",
        description = "category·search·filter·nickname 으로 필터링, page·size 로 페이지네이션한다. " +
            "인증 토큰을 보내면 본인의 좋아요·북마크 여부가 응답에 반영된다. size 는 최대 50 으로 잘린다. " +
            "같은 IP의 검색이 몰리면 의미 검색을 건너뛰고 키워드 검색 결과만 돌려준다(에러 아님).",
    )
    @SecurityRequirements
    @GetMapping
    fun getAllPosts(
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) filter: String?,
        @RequestParam(required = false) nickname: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
        authentication: Authentication?,
        httpRequest: HttpServletRequest,
    ): ApiResponse<PostPageResponse> {
        val currentUserId = authentication.getUserId()
        val allowSemanticSearch =
            search.isNullOrBlank() ||
                rateLimitService.tryConsume(
                    ClientIpResolver.resolve(httpRequest)?.let { "search-embed:ip:$it" },
                    SEARCH_EMBED_IP_LIMIT,
                    SEARCH_EMBED_IP_WINDOW,
                )
        val posts = postService.getAllPosts(category, search, filter, nickname, page, size, currentUserId, allowSemanticSearch)
        return ApiResponse(HttpStatus.OK.value(), "Posts retrieved", posts)
    }

    @Operation(
        summary = "게시글 상세 조회",
        description = "비공개 글을 작성자 본인이 아닌 사용자가 조회하면(비로그인 포함) 존재 자체를 " +
            "숨기기 위해 403 이 아니라 404 로 응답한다. 실패: 404 POST_NOT_FOUND",
    )
    @SecurityRequirements
    @GetMapping("/{id}")
    fun getPostById(
        @PathVariable id: UUID,
        authentication: Authentication?,
    ): ApiResponse<PostResponse> {
        val currentUserId = authentication.getUserId()
        val post = postService.getPostById(id, currentUserId)
        return ApiResponse(HttpStatus.OK.value(), "Post retrieved", post)
    }

    @Operation(
        summary = "게시글 수정",
        description = "HTTP 상태는 200 이고 본문 status 필드만 201 이 아니라 그대로 200 이다. " +
            "URL을 바꾸면 크롤링을 다시 한다(10분 안에 미리 본 URL이면 그 결과를 쓴다). " +
            "실패: 404 POST_NOT_FOUND · 403 FORBIDDEN(작성자 아님) · " +
            "400 INVALID_URL · 400 URL_UNRESOLVABLE · 400 URL_NOT_ALLOWED(URL을 바꾼 경우)",
    )
    @PatchMapping("/{id}")
    fun updatePost(
        @PathVariable id: UUID,
        @RequestBody request: PostUpdateRequest,
        authentication: Authentication,
    ): ApiResponse<PostResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val post = postService.updatePost(id, userId, request)
        return ApiResponse(HttpStatus.OK.value(), "Post updated", post)
    }

    @Operation(
        summary = "게시글 공개 범위 변경",
        description = "isPrivate 만 바꾼다. 실패: 404 POST_NOT_FOUND · 403 FORBIDDEN(작성자 아님)",
    )
    @PatchMapping("/{id}/visibility")
    fun updateVisibility(
        @PathVariable id: UUID,
        @RequestBody request: PostVisibilityUpdateRequest,
        authentication: Authentication,
    ): ApiResponse<PostResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val post = postService.updatePostVisibility(id, userId, request)
        return ApiResponse(HttpStatus.OK.value(), "Post visibility updated", post)
    }

    @Operation(
        summary = "게시글 삭제",
        description = "첨부 이미지 정리 후 삭제한다(댓글은 FK cascade). " +
            "실패: 404 POST_NOT_FOUND · 403 FORBIDDEN(작성자 아님)",
    )
    @DeleteMapping("/{id}")
    fun deletePost(
        @PathVariable id: UUID,
        authentication: Authentication,
    ): ApiResponse<Unit> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        postService.deletePost(id, userId)
        return ApiResponse(HttpStatus.OK.value(), "Post deleted", Unit)
    }
}
