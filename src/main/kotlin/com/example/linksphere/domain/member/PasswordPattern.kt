package com.example.linksphere.domain.member

/**
 * 가입(SignupRequest.password)·비밀번호 변경·재설정(auth 패키지의 newPassword)이 공유하는 비밀번호 형식 규칙.
 * auth가 member를 참조하는 방향이라 member 패키지에 둔다.
 *
 * - 영문·숫자·특수문자를 각각 하나 이상 포함한다.
 * - 출력 가능 ASCII만 허용한다. BCrypt는 72바이트를 넘는 입력을 자르고, 유니코드는 정규화 방식에 따라 같은 문자가
 *   다른 바이트로 인코딩될 수 있어 "분명 같은 비밀번호인데 로그인이 안 되는" 버그로 이어질 수 있다 - 원천 차단한다
 *   (Microsoft Entra ID와 동일한 방식, docs/plans/2026-09-28-auth-hardening.md "확정된 결정들" 참고).
 *
 * 두 규칙을 정규식 하나로 합친 이유: 같은 필드에 `@Pattern`을 두 개 달면 Kotlin이 `@Pattern.List` 하나로 컴파일하는데,
 * swagger-core 2.2.55가 단일 `@Pattern`만 읽어 OpenAPI 스펙에서 규칙이 통째로 빠졌다(2026-11-02 확인). FE가 스펙에서
 * 상수를 생성해 쓰므로 스펙에 실려야 한다.
 */
object PasswordPattern {

    const val REGEXP = "^(?=.*[a-zA-Z])(?=.*[0-9])(?=.*[^a-zA-Z0-9])[\\x20-\\x7E]*$"

    const val MESSAGE =
        "Password must contain at least one letter, one digit, and one special character, using printable ASCII only"
}
