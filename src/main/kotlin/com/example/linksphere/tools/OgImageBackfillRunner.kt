package com.example.linksphere.tools

import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.UrlMetadataExtractor
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 지정한 게시글의 og:image를 강제로 재크롤링해 덮어쓰는 1회성 복구 도구.
 *
 * PostService.updatePost의 재크롤링 경로(title을 비운 PATCH /post/{id})는 기존 ogImage가
 * 이미 값을 갖고 있으면 덮어쓰지 않는다(사용자가 손댄 값을 실수로 되돌리지 않기 위한 설계,
 * PostService.kt 참고) - 그래서 "값은 있지만 죽은" 썸네일(서명 URL 만료, 원본 이미지 삭제 등)은
 * 그 경로로 못 고친다. 대상을 DB 조건(예: og_image IS NULL)으로 못 고르는 이유도 같다 - 죽은
 * URL도 컬럼값은 non-null이라 살아있는 것과 구분이 안 된다. PostAiBackfillRunner가 상태 컬럼으로
 * 못 거르는 대상(원인 5)에 --url-like를 쓰는 것과 같은 이유로, 여기서는 대상을 게시글 ID로 직접
 * 받는다.
 *
 * 2026-09-20, LinkThumbnail 재요청 억제(FE) 작업 중 og:image 429/415/404가 3건 발견됐다 -
 * daumcdn 서명 URL 만료 2건, 삭제된 YouTube 썸네일 1건. 세 URL 모두 원본 페이지를 다시
 * 긁으면 새 서명·해상도로 된 살아있는 og:image를 준다는 것을 확인했다(FE 세션에서 curl로
 * 200 확인).
 *
 * 새 og:image가 실제로 살아있는지는 이 도구가 검증하지 않는다 - 매번 새 HTTP 클라이언트를
 * 두는 대신, dry-run으로 출력된 신규 URL을 --commit 전에 직접 curl로 확인하는 것을 전제로 한다.
 *
 * OrphanImageCleanupRunner/PostAiBackfillRunner와 동일하게 dry-run이 기본이며, 관리자 API가
 * 없는 이 코드베이스에서 admin 성격의 작업은 로컬 실행 도구로만 노출한다.
 *
 * 실행: ./gradlew bootRun --args='--spring.profiles.active=secret,og-image-backfill --post-id=<uuid>,<uuid>'
 *      (dry-run, 보고만)
 *      ./gradlew bootRun --args='--spring.profiles.active=secret,og-image-backfill --post-id=<uuid>,<uuid> --commit'
 *      (실제 덮어쓰기)
 */
@Component
@Profile("og-image-backfill")
class OgImageBackfillRunner(
    private val postRepository: PostRepository,
    private val urlMetadataExtractor: UrlMetadataExtractor,
) : CommandLineRunner {

    override fun run(args: Array<String>) {
        val commit = "--commit" in args
        val postIds =
            args.firstOrNull { it.startsWith("--post-id=") }
                ?.substringAfter("=")
                ?.split(",")
                ?.mapNotNull { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
                .orEmpty()

        if (postIds.isEmpty()) {
            println("--post-id=<uuid>[,<uuid>...] 를 지정하세요.")
            return
        }

        val targets = postRepository.findAllById(postIds)
        println("대상 ${targets.size}건 (요청 ${postIds.size}건)")

        var updated = 0
        var unresolved = 0
        targets.forEach { post ->
            val newOgImage = runCatching { urlMetadataExtractor.extract(post.url).ogImage }.getOrNull()

            if (newOgImage == null) {
                unresolved++
                println("  [미해결] ${post.title} | ${post.url} - 새 og:image를 못 구함")
                return@forEach
            }

            println("  ${post.title} | ${post.url}")
            println("    기존: ${post.ogImage}")
            println("    신규: $newOgImage")

            if (commit) {
                post.ogImage = newOgImage
                postRepository.save(post)
                updated++
            }
        }

        println("해결 가능 ${targets.size - unresolved}건, 미해결 ${unresolved}건")
        if (commit) {
            println("${updated}건 저장 완료")
        } else {
            println("dry-run 모드 - 실제로 덮어쓰려면 --commit을 붙이세요.")
        }
    }
}
