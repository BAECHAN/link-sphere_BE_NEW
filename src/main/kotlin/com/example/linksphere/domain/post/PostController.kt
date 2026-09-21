package com.example.linksphere.domain.post

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Tag(name = "게시글", description = "게시글 등록·조회·수정·삭제")
@RestController
@RequestMapping("/post")
class PostController(private val postService: PostService) {

    @Operation(
        summary = "게시글 등록",
        description = "URL 을 크롤링해 메타데이터를 추출하고 AI 요약을 비동기로 채운다. " +
            "HTTP 상태는 200 이고 본문 status 필드만 201 이다. 실패: 400 INVALID_INPUT(URL 형식 오류)",
    )
    @PostMapping
    fun createPost(
        @RequestBody request: PostCreateRequest,
        authentication: Authentication,
    ): ApiResponse<PostResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val post = postService.createPost(userId, request)
        return ApiResponse(HttpStatus.CREATED.value(), "Post created", post)
    }

    @Operation(
        summary = "게시글 목록 조회",
        description = "category·search·filter·nickname 으로 필터링, page·size 로 페이지네이션한다. " +
            "JWT 를 보내면 본인의 좋아요·북마크 여부가 응답에 반영된다.",
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
    ): ApiResponse<PostPageResponse> {
        val currentUserId = authentication.getUserId()
        val posts = postService.getAllPosts(category, search, filter, nickname, page, size, currentUserId)
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
            "실패: 404 POST_NOT_FOUND · 403 FORBIDDEN(작성자 아님)",
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
