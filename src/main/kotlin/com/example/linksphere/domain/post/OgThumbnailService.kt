package com.example.linksphere.domain.post

import com.example.linksphere.domain.linkpreview.UrlMetadataExtractor
import com.example.linksphere.global.exception.InvalidUrlException
import com.example.linksphere.global.job.JobPublisher
import com.example.linksphere.global.storage.ThumbnailStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** 사본 잡 하나의 결과. 못 만든 이유([NotStored.reason])는 소급 요약 로그에 사이트별로 모인다(OgThumbnailBackfillService). */
sealed interface OgThumbnailResult {
    data class Stored(val url: String) : OgThumbnailResult

    data class NotStored(val reason: String) : OgThumbnailResult
}

/**
 * 피드 카드용 og 이미지 사본(`posts.og_image_thumbnail`)을 만들고 지운다(docs/plans/2026-11-25-og-thumbnail.md).
 *
 * 흐름: PostService가 og_image를 바꾼 트랜잭션에서 사본 주소를 비우고 [PostOgImageChangedEvent]를 낸다 → 커밋 뒤
 * [handleOgImageChanged]가 옛 사본을 지우고 잡 `og-thumbnail`을 맡긴다 → [OgThumbnailJobHandler]가 [createThumbnail]을 부른다.
 * 소급은 [OgThumbnailBackfillService]가 같은 [createThumbnail]을 차례로 부른다.
 *
 * 사본은 원본과 같은 순간을 가리켜야 한다. 컬럼은 저장 직전에 og_image가 잡을 맡길 때와 같을 때만 채우고(조건부 UPDATE), 아니면
 * 막 올린 사본을 지운다 - 주소를 바꾼 뒤 늦게 도착한 옛 잡이 새 글에 옛 그림을 붙이지 않게 한다.
 */
@Service
class OgThumbnailService(
    private val postRepository: PostRepository,
    private val urlMetadataExtractor: UrlMetadataExtractor,
    private val thumbnailStorage: ThumbnailStorage,
    private val jobPublisher: JobPublisher,
) {

    private val logger = LoggerFactory.getLogger(OgThumbnailService::class.java)

    companion object {
        // Mastodon 미디어 첨부 상한(LIMIT)과 같다. 실측 최대 원본 4.8MB를 덮는다
        private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024

        // 원본 받기 한도(UrlMetadataExtractor.safeConnectImage, Jsoup timeout - 조금씩 흘리는 서버면 더 걸릴 수 있다). 8MB도 여유 있게 받는 값이다
        private const val FETCH_TIMEOUT_MILLIS = 10_000

        // 이름에 원본 주소 해시가 들어가 원본이 바뀌면 이름도 바뀐다 - 한 번 올린 이름의 내용은 바뀌지 않는다
        private const val CACHE_CONTROL = "public, max-age=31536000, immutable"

        private val EXTENSIONS = listOf("jpg", "png")

        // og-thumbnails/ 뒤의 이름. 글 id-원본 주소 SHA-256 앞 12자.확장자
        private fun thumbnailName(postId: UUID, ogImage: String, extension: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(ogImage.toByteArray()).joinToString("") { "%02x".format(it) }
            return "$postId-${hash.take(12)}.$extension"
        }
    }

    /**
     * 옛 사본을 지우고 새 사본 잡을 맡긴다. 확장자는 원본을 받아 봐야 알 수 있어 둘 다 지운다(없는 이름이어도 실패하지 않는다). 컬럼
     * 값 대신 이름을 다시 계산해 지우는 이유: 이 수정이 글을 읽은 뒤 커밋하기 전에 옛 잡이 사본을 올렸으면 컬럼으로는 그 사본을 모른다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handleOgImageChanged(event: PostOgImageChangedEvent) {
        event.previousOgImage?.takeIf { it.isNotBlank() }?.let { previous ->
            EXTENSIONS.forEach { extension -> deleteQuietly(thumbnailName(event.postId, previous, extension)) }
        }

        val ogImage = event.ogImage?.takeIf { it.isNotBlank() } ?: return
        jobPublisher.publish(OgThumbnailJobHandler.JOB_NAME, OgThumbnailJobEvent(event.postId, ogImage), "postId: ${event.postId}")
    }

    /**
     * [ogImage]를 받아 줄여 올리고, 글의 og_image가 아직 [ogImage]면 컬럼을 채운다. 못 만들면(받기·형식·상한) 컬럼은 null 그대로라
     * FE가 원본을 그린다. 저장소 오류는 던진다 - 사본 잡은 Lambda 비동기 호출이라 다시 시도된다.
     */
    fun createThumbnail(postId: UUID, ogImage: String): OgThumbnailResult {
        val result = buildAndStore(postId, ogImage)

        when (result) {
            is OgThumbnailResult.Stored -> logger.info("[OgThumbnail] 사본 저장 - postId: $postId, url: ${result.url}")
            is OgThumbnailResult.NotStored -> logger.info("[OgThumbnail] 사본 없음 - postId: $postId, reason: ${result.reason}, host: ${hostOf(ogImage)}")
        }

        return result
    }

    private fun buildAndStore(postId: UUID, ogImage: String): OgThumbnailResult {
        if (!thumbnailStorage.enabled) {
            return OgThumbnailResult.NotStored("storage-off")
        }

        val source =
            when (val fetched = fetch(ogImage)) {
                is Fetched.Bytes -> fetched.bytes
                is Fetched.Failed -> return OgThumbnailResult.NotStored(fetched.reason)
            }

        val resized =
            when (val result = OgThumbnailResizer.resize(source)) {
                is OgThumbnailResizer.Resized -> result
                is OgThumbnailResizer.Rejected -> return OgThumbnailResult.NotStored(result.reason)
            }

        val name = thumbnailName(postId, ogImage, resized.extension)
        val url = thumbnailStorage.put(name, resized.bytes, resized.contentType, CACHE_CONTROL)

        if (postRepository.updateOgImageThumbnailIfOgImage(postId, ogImage, url) == 0) {
            deleteQuietly(name)
            return OgThumbnailResult.NotStored("post-changed")
        }

        return OgThumbnailResult.Stored(url)
    }

    private sealed interface Fetched {
        class Bytes(val bytes: ByteArray) : Fetched

        class Failed(val reason: String) : Fetched
    }

    private fun fetch(ogImage: String): Fetched {
        val response =
            try {
                urlMetadataExtractor.safeConnectImage(ogImage, MAX_SOURCE_BYTES + 1, FETCH_TIMEOUT_MILLIS)
            } catch (e: InvalidUrlException) {
                return Fetched.Failed("url-rejected")
            } catch (e: SocketTimeoutException) {
                return Fetched.Failed("timeout")
            } catch (e: IOException) {
                return Fetched.Failed("fetch-error")
            } catch (e: IllegalStateException) {
                // 리다이렉트가 너무 많거나 Location이 없다(UrlMetadataExtractor.followValidatedRedirects)
                return Fetched.Failed("redirect-error")
            }

        if (response.statusCode() !in 200..299) {
            return Fetched.Failed("status-${response.statusCode()}")
        }

        if (response.contentType()?.lowercase()?.startsWith("image/") != true) {
            return Fetched.Failed("not-image")
        }

        val bytes = response.bodyAsBytes()

        if (bytes.size > MAX_SOURCE_BYTES) {
            return Fetched.Failed("too-large")
        }

        return Fetched.Bytes(bytes)
    }

    // 지우기 실패는 본 요청(글 삭제·수정)이나 잡을 막지 않는다 - SupabaseStorageService.deleteObjectsByPublicUrls와 같은 원칙이다.
    // 남은 사본은 아무 글도 가리키지 않는 파일일 뿐이다
    private fun deleteQuietly(name: String) {
        try {
            thumbnailStorage.delete(name)
        } catch (e: Exception) {
            logger.warn("[OgThumbnail] 사본 지우기 실패 - name: $name, error: ${e.message}")
        }
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: "unknown"
}
