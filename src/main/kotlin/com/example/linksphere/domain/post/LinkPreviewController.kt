package com.example.linksphere.domain.post

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.common.getUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

// /post 아래가 아니라 /link-preview에 둔다 - SecurityConfig가 GET /post/*를 비로그인에게 열어
// 두어서, /post/link-preview는 로그인 없이 크롤링을 시키는 공개 엔드포인트가 돼버린다.
@Tag(name = "게시글", description = "게시글 등록·조회·수정·삭제")
@RestController
class LinkPreviewController(
    private val postService: PostService,
    private val rateLimitService: RateLimitService,
) {

    companion object {
        // URL을 입력할 때마다(디바운스 후) 1회 크롤링한다 - 미리보기만 보고 등록하지 않는 경우도
        // 있어 등록 한도(시간당 20회)보다 넉넉하게 잡는다. docs/TRAFFIC-MANAGEMENT.md 운영 파라미터 참고.
        private val PREVIEW_MEMBER_WINDOW = Duration.ofHours(1)
        private const val PREVIEW_MEMBER_LIMIT = 60
    }

    @Operation(
        summary = "링크 미리보기",
        description = "등록 전에 URL의 제목·설명·썸네일을 가져온다. 결과는 10분간 캐시돼 같은 URL로 등록·수정할 때 " +
            "재사용된다. 실패: 400 INVALID_URL · 400 URL_UNRESOLVABLE(도메인 없음) · 400 URL_NOT_ALLOWED(내부망) · " +
            "403 EMAIL_NOT_VERIFIED · 429 RATE_LIMIT_EXCEEDED(회원당 시간당 한도)",
    )
    @GetMapping("/link-preview")
    fun previewLink(
        @RequestParam url: String,
        authentication: Authentication,
    ): ApiResponse<LinkPreviewResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        // 한도를 이메일 확인·URL 검증보다 먼저 센다 - 잘못된 URL도 검증에서 DNS 조회 비용이 들고,
        // 등록 API(PostController.createPost)도 컨트롤러에서 먼저 센 뒤 서비스로 넘기는 같은 순서다.
        rateLimitService.consume("link-preview:member:$userId", PREVIEW_MEMBER_LIMIT, PREVIEW_MEMBER_WINDOW)
        return ApiResponse(HttpStatus.OK.value(), "Link preview", postService.previewLink(userId, url))
    }
}
