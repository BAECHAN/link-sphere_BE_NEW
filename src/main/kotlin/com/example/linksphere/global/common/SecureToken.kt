package com.example.linksphere.global.common

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 세션(access/refresh)·비밀번호재설정·이메일인증 토큰이 공유하는 발급·해시 로직.
 * 원문 토큰은 절대 저장하지 않는다 - DB가 새도(백업 유출 등) 원문을 복원할 수 없도록
 * sha256 해시만 저장하고, 매 조회 때마다 제시된 원문을 다시 해시해서 비교한다.
 */
object SecureToken {
    private val random = SecureRandom()

    /** 32바이트 무작위 값을 base64url로 인코딩한 불투명 토큰(43자, 패딩 없음)을 생성한다. */
    fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** sha256 해시의 16진 표현(64자)을 반환한다. DB의 CHAR(64) 컬럼과 짝이다. */
    fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
