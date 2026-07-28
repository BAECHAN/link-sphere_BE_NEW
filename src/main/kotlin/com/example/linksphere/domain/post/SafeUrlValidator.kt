package com.example.linksphere.domain.post

import com.example.linksphere.global.exception.InvalidInputException
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/**
 * 사용자가 입력한 URL로 서버가 대신 요청(크롤링)을 보내기 전에 검증한다 (SSRF 방지).
 * 스킴 검사만으로는 내부망·클라우드 메타데이터 엔드포인트(예: 169.254.169.254) 요청을 막지 못하므로,
 * 호스트를 실제로 DNS 해석해 사설/루프백/링크로컬 대역이면 거부한다.
 * 리다이렉트를 따라갈 때도 매 홉마다 이 검증을 다시 통과해야 한다 — 공개 URL이 내부 주소로
 * 리다이렉트하는 우회를 막기 위함이다.
 */
@Component
class SafeUrlValidator {

    fun validate(url: String) {
        if (url.isBlank()) throw InvalidInputException("URL cannot be blank")

        val uri =
            try {
                URI(url)
            } catch (e: URISyntaxException) {
                throw InvalidInputException("Invalid URL format: $url")
            }

        if (uri.scheme !in listOf("http", "https")) {
            throw InvalidInputException("URL must use http or https scheme")
        }

        val host = uri.host
        if (host.isNullOrBlank()) throw InvalidInputException("Invalid URL format: $url")

        val addresses =
            try {
                InetAddress.getAllByName(host)
            } catch (e: Exception) {
                throw InvalidInputException("Cannot resolve host: $host")
            }

        val isPrivate =
            addresses.any {
                it.isLoopbackAddress ||
                    it.isSiteLocalAddress ||
                    it.isLinkLocalAddress ||
                    it.isAnyLocalAddress ||
                    it.isMulticastAddress
            }
        if (isPrivate) throw InvalidInputException("URL points to a private network address: $url")
    }
}
