package com.example.linksphere.domain.member

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// 회원 행만 읽고 쓰는 계정 API. 경로(/auth/account…)와 태그는 AuthController에 있던 때와 같다 - 세션을 다루는 비밀번호 변경·탈퇴는
// AuthController에 남는다(docs/plans/2026-11-15-be-structure-stage1.md). 태그 문자열은 AuthController와 글자까지 같아야
// OpenAPI에서 하나로 합쳐진다.
@Tag(name = "인증", description = "회원가입·로그인·토큰 갱신·계정 조회")
@RestController
@RequestMapping("/auth")
@Validated // @RequestParam(개별 메서드 파라미터)에 붙인 @Size가 동작하려면 클래스 레벨에 필요하다
class MemberController(private val memberService: MemberService) {

    @Operation(
        summary = "내 계정 조회",
        description = "인증된 세션이 가리키는 회원 ID로 조회한다.",
    )
    @GetMapping("/account")
    fun getAccount(authentication: Authentication): ApiResponse<AccountResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        return ApiResponse(HttpStatus.OK.value(), "Account retrieved", memberService.getAccount(userId))
    }

    @Operation(
        summary = "내 계정 수정",
        description = "nickname·image 만 부분 수정한다(null 인 필드는 유지). " +
            "실패: 409 DUPLICATE_NICKNAME(닉네임 중복) · 403 ACCESS_DENIED(데모 계정처럼 이 권한이 없는 역할)",
    )
    @PreAuthorize("hasAuthority('ACCOUNT_PROFILE_UPDATE')")
    @PatchMapping("/account")
    fun updateAccount(
        @Valid @RequestBody request: UpdateAccountRequest,
        authentication: Authentication,
    ): ApiResponse<AccountResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        return ApiResponse(HttpStatus.OK.value(), "Account updated", memberService.updateAccountAndGet(userId, request))
    }

    // 마이페이지(로그인)와 가입 화면(비로그인) 둘 다에서 쓴다 - permitAll 경로라 인증 안 된
    // 요청은 authentication이 null이 아니라 이름이 "anonymousUser"인 익명 토큰으로 들어오고,
    // getUserId()가 UUID 파싱에 실패해 null을 반환한다(MemberService가 그 null을 "본인 제외 없이
    // 순수 존재 여부만 확인"으로 처리)
    @Operation(
        summary = "닉네임 사용 가능 여부 확인",
        description = "비로그인도 호출할 수 있다. 로그인 상태로 보내면 본인 닉네임은 사용 가능으로 판정한다.",
    )
    @SecurityRequirements
    @GetMapping("/account/nickname-availability")
    fun checkNicknameAvailability(
        @RequestParam @Size(max = 20) nickname: String,
        authentication: Authentication?,
    ): ApiResponse<NicknameAvailabilityResponse> = ApiResponse(
        HttpStatus.OK.value(),
        "Nickname availability checked",
        memberService.checkNicknameAvailability(authentication.getUserId(), nickname),
    )
}
