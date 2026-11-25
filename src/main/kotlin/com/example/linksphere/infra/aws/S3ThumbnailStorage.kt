package com.example.linksphere.infra.aws

import com.example.linksphere.global.storage.ThumbnailStorage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Duration

/**
 * 운영용 — 사본을 FE 버킷(서울)의 `og-thumbnails/`에 올리고 지운다. CloudFront가 같은 접두어를 사이트 주소(`linksphere.click/og-thumbnails/`)로
 * 낸다. API Lambda 역할에 이 접두어의 PutObject·DeleteObject만 있다(infra `lib/baseline-stack.ts`). FE 배포의 `s3 sync --delete`는
 * 이 접두어를 빼고 동기화한다(FE deploy.yml). LambdaHandler가 init에서 `app.og-thumbnail.storage=s3`를 넣어 이 구현을 고른다.
 *
 * 형식과 캐시 헤더는 S3 객체 메타데이터로 직접 단다 - CloudFront는 S3가 준 Content-Type·Cache-Control을 그대로 낸다.
 */
@Component
@ConditionalOnProperty(name = ["app.og-thumbnail.storage"], havingValue = "s3")
class S3ThumbnailStorage internal constructor(
    private val bucket: String,
    private val keyPrefix: String,
    private val publicBaseUrl: String,
    clientFactory: () -> S3Client,
) : ThumbnailStorage {

    @Autowired
    constructor(
        @Value("\${app.og-thumbnail.bucket}") bucket: String,
        @Value("\${app.og-thumbnail.region}") region: String,
        @Value("\${app.og-thumbnail.key-prefix}") keyPrefix: String,
        @Value("\${app.og-thumbnail.public-base-url}") publicBaseUrl: String,
    ) : this(
        bucket,
        keyPrefix,
        publicBaseUrl,
        {
            // 버킷은 서울, Lambda는 도쿄라 리전을 설정으로 따로 받는다. 지우기는 글 삭제·수정 요청이 커밋된 뒤 그 요청 안에서 돈다 -
            // S3가 느려도 요청이 오래 붙잡히지 않게 재시도를 합친 한 번의 호출을 10초로 끊는다(SupabaseStorageService 읽기 한도와 같은 값)
            S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration { it.apiCallTimeout(Duration.ofSeconds(10)) }
                .build()
        },
    )

    // 실제 클라이언트는 복원 뒤 첫 호출에서 만든다. SDK 초기화는 LambdaHandler.warmUpAwsSdk가 스냅샷 전에 해 둔다(LambdaSelfInvoker와 같다)
    private val s3Client: S3Client by lazy(clientFactory)

    override val enabled = true

    override fun put(name: String, bytes: ByteArray, contentType: String, cacheControl: String): String {
        s3Client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(keyPrefix + name).contentType(contentType).cacheControl(cacheControl).build(),
            RequestBody.fromBytes(bytes),
        )
        return publicBaseUrl + name
    }

    override fun delete(name: String) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(keyPrefix + name).build())
    }
}
