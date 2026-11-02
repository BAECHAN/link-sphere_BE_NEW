package com.example.linksphere.domain.member

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

// 가입·계정 수정 요청은 AuthController가 받지만 그대로 MemberService로 넘어간다 — member 패키지에 둬야 member가 auth를 알지
// 않는다(2026-10-26, 패키지 순환 member ↔ auth 해소). OpenAPI 스키마 이름은 클래스 이름이라 그대로다.

data class SignupRequest(
    @field:NotBlank
    @field:Email
    val email: String,
    // 형식만 검증한다 - 실제 도달 가능한 주소인지는 가입 후 인증메일 확인으로 검증한다
    // (미인증이어도 로그인은 되고, 글쓰기·댓글쓰기만 막힌다 - AuthService.signup 참고)
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
