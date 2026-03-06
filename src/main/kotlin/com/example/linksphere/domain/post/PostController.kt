package com.example.linksphere.domain.post

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import com.example.linksphere.infra.sse.SseEmitterService
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/post")
class PostController(
        private val postService: PostService,
        private val sseEmitterService: SseEmitterService
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(PostController::class.java)

    @PostMapping
    fun createPost(
            @RequestBody request: PostCreateRequest,
            authentication: Authentication
    ): ApiResponse<PostResponse> {
        val userId = UUID.fromString(authentication.name)
        val post = postService.createPost(userId, request)
        return ApiResponse(HttpStatus.CREATED.value(), "Post created", post)
    }

    @GetMapping
    fun getAllPosts(
            @RequestParam(required = false) category: String?,
            @RequestParam(required = false) search: String?,
            @RequestParam(required = false) filter: String?,
            @RequestParam(required = false) nickname: String?,
            @RequestParam(defaultValue = "0") page: Int,
            @RequestParam(defaultValue = "10") size: Int,
            authentication: Authentication?
    ): ApiResponse<PostPageResponse> {
        val currentUserId = authentication.getUserId()
        val posts = postService.getAllPosts(category, search, filter, nickname, page, size, currentUserId)
        return ApiResponse(HttpStatus.OK.value(), "Posts retrieved", posts)
    }

    @GetMapping("/{id}")
    fun getPostById(
            @PathVariable id: UUID,
            authentication: Authentication?
    ): ApiResponse<PostResponse> {
        val currentUserId = authentication.getUserId()
        val post = postService.getPostById(id, currentUserId)
        return ApiResponse(HttpStatus.OK.value(), "Post retrieved", post)
    }

    @PatchMapping("/{id}")
    fun updatePost(
            @PathVariable id: UUID,
            @RequestBody request: PostUpdateRequest,
            authentication: Authentication
    ): ApiResponse<PostResponse> {
        val userId = UUID.fromString(authentication.name)
        val post = postService.updatePost(id, userId, request)
        return ApiResponse(HttpStatus.OK.value(), "Post updated", post)
    }

    @PatchMapping("/{id}/visibility")
    fun updateVisibility(
            @PathVariable id: UUID,
            @RequestBody request: PostVisibilityUpdateRequest,
            authentication: Authentication
    ): ApiResponse<PostResponse> {
        val userId = UUID.fromString(authentication.name)
        val post = postService.updatePostVisibility(id, userId, request)
        return ApiResponse(HttpStatus.OK.value(), "Post visibility updated", post)
    }

    @DeleteMapping("/{id}")
    fun deletePost(
            @PathVariable id: UUID,
            authentication: Authentication
    ): ApiResponse<Unit> {
        val userId = UUID.fromString(authentication.name)
        postService.deletePost(id, userId)
        return ApiResponse(HttpStatus.OK.value(), "Post deleted", Unit)
    }

    // Lambda 환경에서 SSE(Server-Sent Events)는 동작하지 않는다.
    // Lambda는 요청-응답 1회 모델이므로 연결을 유지할 수 없고,
    // SseEmitter 대기로 인해 Lambda 30초 타임아웃 → 502가 발생한다.
    // AI 분석 결과는 POST /post 응답 또는 GET /post/{id} 로 확인한다.
    @GetMapping("/ai-events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun subscribeAiEvents(): ResponseEntity<ApiResponse<String>> {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse(HttpStatus.SERVICE_UNAVAILABLE.value(), "SSE is not supported in this environment. Use GET /post/{id} to check AI analysis status.", ""))
    }
}
