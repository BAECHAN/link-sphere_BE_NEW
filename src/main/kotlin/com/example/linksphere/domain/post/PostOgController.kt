package com.example.linksphere.domain.post

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

@Tag(name = "링크 미리보기 HTML", description = "메신저 미리보기 봇이 읽는 글 주소의 OG 태그")
@RestController
@RequestMapping("/og")
class PostOgController(private val postOgService: PostOgService) {

    // ApiResponse로 감싸지 않는 유일한 엔드포인트다 - 봇은 JSON을 읽지 않고 HTML의 <meta property="og:*">만 읽는다
    // (ControllerSignatureRulesTest의 HTML_ENDPOINTS)
    @Operation(
        summary = "글 주소의 OG 태그 HTML",
        description =
        "CloudFront Function이 미리보기 봇의 `/post/{postId}` 요청만 이리로 보낸다. 항상 비로그인으로 보고, 볼 수 없는 글·없는 글·" +
            "UUID가 아닌 id면 사이트 이름·소개만 싣는다(200).",
    )
    @SecurityRequirements
    // produces를 걸지 않는다 - 걸면 Accept에 HTML이 없는 봇이 매핑 단계에서 406을 받는다. Content-Type은 아래 contentType()이 정하고,
    // 미리 정해진 Content-Type이 있으면 Spring이 Accept 협상을 건너뛴다(spring-webmvc 6.2.19
    // AbstractMessageConverterMethodProcessor.writeWithMessageConverters의 isContentTypePreset)
    @GetMapping("/post/{postId}")
    fun getPostOgHtml(@PathVariable postId: String): ResponseEntity<String> = ResponseEntity.ok()
        .cacheControl(OG_HTML_CACHE_CONTROL)
        .contentType(TEXT_HTML_UTF8)
        .body(postOgService.renderPostHtml(postId))

    companion object {
        // 기본(S3) 비헤이비어의 CloudFront 캐시(Managed-CachingOptimized)가 이 값을 따라 같은 글을 5분 동안 한 번만 Lambda에 묻는다.
        // 글을 고치거나 비공개로 바꾸면 메신저 미리보기에는 최대 5분 늦게 반영된다(봇 자체 캐시는 별도). 선례: CategoryController
        private val OG_HTML_CACHE_CONTROL: CacheControl = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()

        // 한글 제목이 깨지지 않게 charset을 직접 싣는다
        private val TEXT_HTML_UTF8: MediaType = MediaType("text", "html", Charsets.UTF_8)
    }
}
