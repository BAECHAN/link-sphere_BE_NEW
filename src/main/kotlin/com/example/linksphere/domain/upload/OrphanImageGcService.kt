package com.example.linksphere.domain.upload

import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.global.common.SupabaseStorageService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

private val URL_REGEX = Regex("""https?://\S+""")

/**
 * 아무도 쓰지 않는 업로드 이미지(고아)를 지운다. EventBridge cron(4일마다, link-sphere-feed-crawl
 * 룰을 feed-crawl·account-purge와 공유) → LambdaHandler("orphan-image-gc")와 로컬 도구
 * OrphanImageCleanupRunner가 함께 쓴다.
 *
 * 업로드는 클라이언트가 Supabase에 직접 하고 서버는 그 사실을 모른다. 그래서 업로드는 성공했는데
 * 댓글·프로필 저장이 실패하거나, 커밋 후 스토리지 삭제가 실패하거나, 아바타를 바꿔 옛 파일이 남는
 * 경우를 이 참조 스캔이 최종적으로 회수한다. Supabase Storage에는 현재 객체를 자동 만료하는 규칙이
 * 없어(lifecycle은 이전 버전 만료만 지원) 직접 정리해야 한다(docs/plans/2026-10-05-image-upload-lifecycle.md).
 *
 * 판정은 "버킷 객체 - (모든 댓글 본문 + 모든 회원 아바타에 든 우리 버킷 URL)" 중 MIN_AGE가 지난 것이다.
 * 삭제는 되돌릴 수 없으므로 참조 집합이 비었는데 후보가 있으면(DB 조회 이상 신호) 지우지 않고 멈추고,
 * 한 번에 MAX_DELETE_PER_RUN개까지만 지우며, AccountPurgeService와 같은 90초 마감을 둔다.
 */
@Service
class OrphanImageGcService(
    private val commentRepository: CommentRepository,
    private val memberRepository: MemberRepository,
    private val supabaseStorageService: SupabaseStorageService,
) {

    private val logger = LoggerFactory.getLogger(OrphanImageGcService::class.java)

    companion object {
        // 업로드는 제출 직전에 일어나고 서명 업로드 URL은 2시간 유효하다(Supabase createSignedUploadUrl
        // 문서) - 24시간이 지났는데 아무도 쓰지 않으면 제출이 실패했거나 버려진 파일이다.
        val MIN_AGE: Duration = Duration.ofHours(24)
        const val MAX_DELETE_PER_RUN = 500
        private const val DELETE_CHUNK_SIZE = 100
        private const val DEADLINE_MILLIS = 90_000L
    }

    /**
     * total: 버킷 전체 객체 수, referenced: 참조된 우리 버킷 URL 수, candidates: 지울 대상(dry-run이면
     * 보고만), deleteRequested: 실제 삭제를 요청한 수(스토리지 삭제 실패는 SupabaseStorageService가 로그만
     * 남기므로 다음 실행이 다시 후보로 잡는다), aborted: 안전 중단 여부.
     */
    data class GcSummary(
        val total: Int,
        val referenced: Int,
        val candidates: List<String>,
        val deleteRequested: Int,
        val aborted: Boolean,
    )

    fun collect(dryRun: Boolean): GcSummary = collect(dryRun, Instant.now())

    // now를 받는 쪽은 테스트용이다 - 기본 인자로 두면 호출부를 mock할 때 매번 다른 now가 넘어가 스텁이 안 맞는다.
    fun collect(dryRun: Boolean, now: Instant): GcSummary {
        val deadline = System.currentTimeMillis() + DEADLINE_MILLIS

        // 버킷을 먼저 읽고 참조를 나중에 읽는다 - 사이에 새로 생긴 참조까지 반영해 덜 지우는 쪽으로 기운다.
        val objects = supabaseStorageService.listAllObjects()
        val referenced =
            (commentRepository.findAllContent() + memberRepository.findAllImageUrls())
                .flatMap { URL_REGEX.findAll(it).map(MatchResult::value) }
                .filter { supabaseStorageService.isManagedUrl(it) }
                .toSet()

        val cutoff = now.minus(MIN_AGE)
        val candidates =
            objects
                .filter { it.createdAt?.isBefore(cutoff) == true }
                .map { it.publicUrl }
                .filterNot { it in referenced }

        if (referenced.isEmpty() && candidates.isNotEmpty()) {
            logger.warn("[OrphanImageGc] 참조가 0건인데 후보가 ${candidates.size}개 - DB 조회 이상일 수 있어 삭제하지 않고 중단")
            return GcSummary(objects.size, 0, candidates, 0, aborted = true)
        }

        var deleteRequested = 0

        if (!dryRun) {
            for (chunk in candidates.take(MAX_DELETE_PER_RUN).chunked(DELETE_CHUNK_SIZE)) {
                if (System.currentTimeMillis() > deadline) {
                    logger.warn("[OrphanImageGc] 90초 마감 초과 - 남은 후보는 다음 실행으로 미룸")
                    break
                }

                supabaseStorageService.deleteObjectsByPublicUrls(chunk)
                deleteRequested += chunk.size
            }
        }

        logger.info(
            "[OrphanImageGc] 완료 - dryRun=$dryRun, 전체=${objects.size}, 참조=${referenced.size}, " +
                "후보=${candidates.size}, 삭제요청=$deleteRequested",
        )
        return GcSummary(objects.size, referenced.size, candidates, deleteRequested, aborted = false)
    }
}
