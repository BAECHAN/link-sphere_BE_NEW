package com.example.linksphere

import org.apache.tomcat.util.http.fileupload.FileUpload
import org.apache.tomcat.util.http.fileupload.UploadContext
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * multipart/form-data의 파일 파트 하나. data class 기본 equals/hashCode는 ByteArray를
 * 참조로 비교하므로(내용이 같아도 다른 인스턴스면 다르다고 판정), bytes만
 * contentEquals/contentHashCode로 오버라이드해 내용 비교가 되게 한다.
 */
internal data class ParsedMultipartFile(
    val fieldName: String,
    val filename: String?,
    val contentType: String?,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedMultipartFile) return false
        return fieldName == other.fieldName &&
            filename == other.filename &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = fieldName.hashCode()
        result = 31 * result + (filename?.hashCode() ?: 0)
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

internal data class ParsedMultipart(
    val fields: Map<String, List<String>>,
    val files: List<ParsedMultipartFile>,
)

/**
 * raw multipart/form-data 바이트를 Tomcat의 스트리밍 파서(FileUpload)로 직접 해석한다.
 *
 * 임시방편(2026-08-02) — aws-serverless-java-container-springboot3로 LambdaHandler 전체를
 * 교체하기 전까지의 stopgap이다. `LambdaHandler`는 MockMvc로 raw 멀티파트 바이트를
 * `.content()`에 그냥 넣기만 했는데, `MockHttpServletRequest.getParts()`는 사전 등록된
 * Part만 반환하고 raw body를 스스로 파싱하지 않는다(spring-test는 테스트 전용 더블이라
 * 파싱 주체였던 서블릿 컨테이너 자체가 없다) — 그래서 `@RequestParam`/`@RequestPart`가
 * 클라이언트가 무엇을 보냈는지와 무관하게 항상 null로 바인딩됐다.
 *
 * `org.apache.tomcat.util.http.fileupload.*`는 Tomcat의 공개 API가 아닌 내부 유틸리티라서
 * tomcat-embed-core 버전이 오르면 시그니처가 예고 없이 바뀔 수 있다 — 장기 해법이 아니다.
 *
 * spring-test 타입(`MockMultipartFile`, `MockMultipartHttpServletRequestBuilder`)을 참조하지
 * 않는다 — 파싱 결과를 어떤 프레임워크 타입에 등록할지는 호출자(`LambdaHandler`)의 책임이다.
 * 덕분에 무거운 `LambdaHandler`의 companion object init(Spring Boot 전체 부팅)을 거치지 않고
 * 이 파서만 단위 테스트할 수 있다.
 */
internal object MultipartRequestParser {
    fun parse(
        bytes: ByteArray,
        contentType: String,
    ): ParsedMultipart {
        val requestContext =
            object : UploadContext {
                override fun getCharacterEncoding() = "UTF-8"

                override fun getContentType() = contentType

                override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)

                override fun contentLength() = bytes.size.toLong()
            }

        val fields = mutableMapOf<String, MutableList<String>>()
        val files = mutableListOf<ParsedMultipartFile>()

        val items = FileUpload().getItemIterator(requestContext)
        while (items.hasNext()) {
            val item = items.next()
            val itemBytes = item.openStream().readBytes()
            if (item.isFormField) {
                fields.getOrPut(item.fieldName) { mutableListOf() }.add(String(itemBytes, Charsets.UTF_8))
            } else {
                files.add(ParsedMultipartFile(item.fieldName, item.name, item.contentType, itemBytes))
            }
        }

        return ParsedMultipart(fields, files)
    }
}
