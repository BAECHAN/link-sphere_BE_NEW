package com.example.linksphere.domain.post

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.infra.sse.SseEmitterService
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/post")
class PostController(
        private val postService: PostService,
        private val sseEmitterService: SseEmitterService
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(PostController::class.java)

    private fun getUserIdFromAuthentication(
            authentication: org.springframework.security.core.Authentication?
    ): UUID? {
        if (authentication == null) return null
        return try {
            UUID.fromString(authentication.name)
        } catch (e: Exception) {
            logger.warn("Failed to parse UUID from auth name: ${authentication.name}")
            null
        }
    }

    @PostMapping
    fun createPost(
            @RequestBody request: PostCreateRequest,
            authentication: org.springframework.security.core.Authentication
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
            @RequestParam(defaultValue = "0") page: Int,
            @RequestParam(defaultValue = "10") size: Int,
            authentication: org.springframework.security.core.Authentication?
    ): ApiResponse<PostPageResponse> {
        val currentUserId = getUserIdFromAuthentication(authentication)
        val posts = postService.getAllPosts(category, search, filter, page, size, currentUserId)
        return ApiResponse(HttpStatus.OK.value(), "Posts retrieved", posts)
    }

    @GetMapping("/{id}")
    fun getPostById(
            @PathVariable id: UUID,
            authentication: org.springframework.security.core.Authentication?
    ): ApiResponse<PostResponse> {
        val currentUserId = getUserIdFromAuthentication(authentication)
        val post = postService.getPostById(id, currentUserId)
        return ApiResponse(HttpStatus.OK.value(), "Post retrieved", post)
    }

    @DeleteMapping("/{id}")
    fun deletePost(
            @PathVariable id: UUID,
            authentication: org.springframework.security.core.Authentication
    ): ApiResponse<Unit> {
        val userId = UUID.fromString(authentication.name)
        postService.deletePost(id, userId)
        return ApiResponse(HttpStatus.OK.value(), "Post deleted", Unit)
    }

    @GetMapping("/ai-events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun subscribeAiEvents(
            authentication: org.springframework.security.core.Authentication?
    ): SseEmitter {
        logger.info("[PostController] SSE Subscription request. Auth: ${authentication?.name}")
        if (authentication == null) {
            logger.error("[PostController] SSE Subscription attempted with NULL authentication")
            throw RuntimeException("Authentication is missing")
        }
        logger.info(
                "[PostController] SSE Subscription request. Auth name: ${authentication.name}, Principal: ${authentication.principal}"
        )
        val userId =
                try {
                    UUID.fromString(authentication.name)
                } catch (e: Exception) {
                    logger.error(
                            "[PostController] Failed to parse UUID from auth id: ${authentication.name}",
                            e
                    )
                    throw e
                }
        return sseEmitterService.subscribe(userId)
    }
}
