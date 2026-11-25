package com.example.linksphere.global.storage

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 사본을 만들지 않는다. 로컬·테스트의 기본값(`app.og-thumbnail.storage=skip`)이다 — 로컬에서 글을 등록할 때마다 남의 사이트에서
 * 이미지를 받거나 운영 버킷에 쓰지 않게 한다. 이렇게 만든 글은 `og_image_thumbnail`이 null이라 FE가 원본을 그린다.
 */
@Component
@ConditionalOnProperty(name = ["app.og-thumbnail.storage"], havingValue = "skip")
class SkippingThumbnailStorage : ThumbnailStorage {

    override val enabled = false

    override fun put(name: String, bytes: ByteArray, contentType: String, cacheControl: String): String = throw IllegalStateException("og thumbnail storage is off (app.og-thumbnail.storage=skip)")

    override fun delete(name: String) = Unit
}
