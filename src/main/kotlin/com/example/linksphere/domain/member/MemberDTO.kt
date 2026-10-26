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
    @field:Pattern(
        regexp = "^(?=.*[a-zA-Z])(?=.*[0-9])(?=.*[^a-zA-Z0-9]).*$",
        message = "Password must contain at least one letter, one digit, and one special character",
    )
    // BCrypt는 72바이트를 넘는 입력을 자르고, 유니코드는 정규화 방식에 따라 같은 문자가
    // 다른 바이트로 인코딩될 수 있어 "분명 같은 비밀번호인데 로그인이 안 되는" 버그로
    // 이어질 수 있다 - 출력 가능 ASCII만 허용해 원천 차단한다(Microsoft Entra ID와 동일한
    // 방식, docs/plans/2026-09-28-auth-hardening.md "확정된 결정들" 참고).
    @field:Pattern(
        regexp = "^[\\x20-\\x7E]*$",
        message = "Password must contain only printable ASCII characters",
    )
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
