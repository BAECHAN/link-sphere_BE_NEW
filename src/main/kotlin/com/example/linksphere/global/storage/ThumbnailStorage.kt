package com.example.linksphere.global.storage

/**
 * 피드 카드용 og 이미지 사본을 올리고 지우는 곳. 구현은 `app.og-thumbnail.storage` 설정이 고른다(application.yml) —
 * 운영은 FE 버킷의 `og-thumbnails/`(infra/aws/S3ThumbnailStorage, LambdaHandler가 init에서 s3를 넣는다), 로컬·테스트는
 * 아무것도 하지 않는 [SkippingThumbnailStorage]다. 발행 구현을 `app.job.publisher`가 고르는 것과 같은 방식이다(global/job/JobPublisher).
 */
interface ThumbnailStorage {
    /** false면 사본을 만들지 않는다. 원본도 받지 않는다(OgThumbnailService). */
    val enabled: Boolean

    /** 접두어 뒤의 이름([name])으로 올리고, 브라우저가 받을 절대 주소를 돌려준다. */
    fun put(name: String, bytes: ByteArray, contentType: String, cacheControl: String): String

    /** 없는 이름이어도 실패하지 않는다. */
    fun delete(name: String)
}
