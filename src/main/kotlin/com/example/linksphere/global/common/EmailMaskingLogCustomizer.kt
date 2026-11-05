package com.example.linksphere.global.common

import org.springframework.boot.json.JsonWriter
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer
import java.util.function.UnaryOperator

/**
 * Lambda의 JSON 로그에 이메일 원문이 남지 않게 모든 문자열 값(message·stack_trace 등)에서 가린다.
 * 찍는 지점마다 고치면 우리 코드가 아닌 곳을 못 잡는다 - Hibernate의 유니크 위반 로그
 * (`Detail: Key (lower(email))=(...)`)와 SES 예외 메시지가 그렇다. LambdaHandler가 기동 인자
 * `logging.structured.json.customizer`로 등록하며, 텍스트로 찍는 로컬 로그는 그대로다.
 * 왜 로그만 가리고 컬럼은 암호화하지 않는지는 docs/DATA-PROTECTION.md.
 */
class EmailMaskingLogCustomizer : StructuredLoggingJsonMembersCustomizer<Any> {

    override fun customize(members: JsonWriter.Members<Any>) {
        members.applyingValueProcessor(
            JsonWriter.ValueProcessor.of(String::class.java, UnaryOperator { EMAIL.replace(it, MASK) }),
        )
    }

    private companion object {
        val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        const val MASK = "<email>"
    }
}
