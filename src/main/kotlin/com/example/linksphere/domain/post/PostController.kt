package com.example.linksphere.domain.post

import java.util.UUID
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/post")
class PostController(private val postService: PostService) {

    @PostMapping
    fun createPost(
            @RequestBody request: PostCreateRequest,
            authentication: org.springframework.security.core.Authentication
    ): PostResponse {
        val userId = UUID.fromString(authentication.name)
        return postService.createPost(userId, request)
    }

    @GetMapping
    fun getAllPosts(@RequestParam(required = false) category: String?): List<PostResponse> {
        return if (category != null) {
            postService.getPostsByCategorySlug(category)
        } else {
            postService.getAllPosts()
        }
    }

    @GetMapping("/{id}")
    fun getPostById(@PathVariable id: UUID): PostResponse {
        return postService.getPostById(id)
    }
}
