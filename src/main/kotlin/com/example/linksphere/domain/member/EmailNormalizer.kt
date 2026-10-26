package com.example.linksphere.domain.member

/**
 * 이메일 대소문자·공백만 다른 계정이 별개로 생기는 것을 막는다 - Gmail/Outlook 등 주요 서비스도 저장 전 소문자로 정규화한다
 * (RFC 5321은 local-part를 대소문자 구분하도록 규정하지만, 같은 문서가 실제 활용은 상호운용성을 해치므로 권장하지 않는다고
 * 명시한다). 조회·저장·레이트리밋 키가 모두 같은 형태를 써야 해서 한 곳에 둔다. DB 유니크 인덱스도 lower(email)이다.
 */
object EmailNormalizer {

    fun normalize(email: String): String = email.trim().lowercase()
}
