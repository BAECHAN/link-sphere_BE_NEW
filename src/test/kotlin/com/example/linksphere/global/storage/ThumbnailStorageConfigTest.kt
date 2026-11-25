package com.example.linksphere.global.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

/**
 * 사본 저장 설정에서 바뀌면 조용히 깨지는 값들을 고정한다.
 *
 * - 기본값 skip: 로컬·테스트가 운영 버킷에 쓰거나 남의 사이트에서 이미지를 받지 않는다. 운영은 LambdaHandler가 s3로 바꾼다.
 * - 접두어: FE 배포의 `s3 sync --delete`가 이 접두어 아래를 빼고 동기화한다(FE deploy.yml의 exclude). 이름이 어긋나면 FE 배포 때마다
 *   사본이 지워진다.
 * - 공개 기준 주소: CloudFront가 같은 접두어를 S3로 보낸다. 컬럼에 이 주소로 시작하는 절대 주소가 들어간다.
 */
class ThumbnailStorageConfigTest {

    @Test
    fun `저장 기본값은 건너뛰기이고 접두어와 공개 주소는 FE 배포 제외 경로와 같다`() {
        val base = YamlPropertySourceLoader().load("application", ClassPathResource("application.yml")).first()

        assertEquals("skip", base.getProperty("app.og-thumbnail.storage"))
        assertEquals("link-sphere-fe-bucket", base.getProperty("app.og-thumbnail.bucket"))
        assertEquals("og-thumbnails/", base.getProperty("app.og-thumbnail.key-prefix"))
        assertEquals("https://linksphere.click/og-thumbnails/", base.getProperty("app.og-thumbnail.public-base-url"))
    }
}
