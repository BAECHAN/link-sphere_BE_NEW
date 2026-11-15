package com.example.linksphere.domain.member

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

// 가입 요청은 AuthController가, 계정 수정 요청은 MemberController가 받아 MemberService로 넘긴다 — member 패키지에 둬야 member가
// auth를 알지 않는다(2026-10-26, 패키지 순환 member ↔ auth 해소). OpenAPI 스키마 이름은 클래스 이름이라 그대로다.

data class SignupRequest(
    // 형식만 검증한다 - 실제 도달 가능한 주소인지는 가입 후 인증메일 확인으로 검증한다
    // (미인증이어도 로그인은 되고, 글쓰기·댓글쓰기만 막힌다 - AuthService.signup 참고)
    @field:NotBlank
    @field:Email
    val email: String,
    @field:Size(min = 8, max = 64)
    // 영문·숫자·특수문자 조합 + 출력 가능 ASCII 전용(근거는 PasswordPattern)
    @field:Pattern(regexp = PasswordPattern.REGEXP, message = PasswordPattern.MESSAGE)
    val password: String,
    @field:NotBlank
    @field:Size(min = 2, max = 20)
    @field:Pattern(regexp = "^[a-zA-Z0-9가-힣_.-]*$")
    val nickname: String,
)

data class UpdateAccountRequest(
    // null 이면 그 필드는 유지한다(부분 수정) - SignupRequest.nickname과 동일한 형식 규칙이지만
    // 여긴 선택 입력이라 @NotBlank는 붙이지 않는다.
    @field:Size(min = 2, max = 20)
    @field:Pattern(regexp = "^[a-zA-Z0-9가-힣_.-]*$")
    val nickname: String? = null,
    @field:Size(max = 2048)
    val image: String? = null,
)

// 가입 응답(AuthService.signup)도 이 DTO를 쓴다 - 회원 행을 그대로 옮긴 모양이라 member에 둔다.
data class AccountResponse(
    val id: String,
    val nickname: String? = null,
    val role: String = "USER", // Default role
    val image: String? = null,
    // 미인증이어도 로그인은 성공하므로 로그인된 사람도 false일 수 있다 - FE가 배지·글쓰기
    // 차단 판단에 쓴다.
    val emailVerified: Boolean = false,
    // FE의 이메일 인증 재발송 버튼이 이 값을 그대로 요청 body에 실어 보낸다(재발송 API는
    // 로그인 여부와 무관하게 이메일만으로 호출하도록 설계돼 세션에서 유추하지 않는다 -
    // AuthController.requestEmailVerification 참고). 이 DTO의 다른 필드처럼 기본값을 둔다.
    val email: String = "",
) {
    companion object {
        fun from(member: TableMember): AccountResponse = AccountResponse(
            id = member.id.toString(),
            nickname = member.nickname,
            role = member.role.name,
            image = member.image,
            emailVerified = member.emailVerified,
            email = member.email,
        )
    }
}

data class NicknameAvailabilityResponse(val available: Boolean)
