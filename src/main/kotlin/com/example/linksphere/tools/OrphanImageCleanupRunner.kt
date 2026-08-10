package com.example.linksphere.tools

import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.global.common.SupabaseStorageService
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

private val URL_REGEX = Regex("""https?://\S+""")

/**
 * 서명 URL만 발급받고 댓글·프로필 수정으로 실제 제출되지 않은 고아 이미지를 찾아 보고한다.
 * 자동화된 스케줄 잡이 아니라 개발자가 필요할 때 로컬에서 직접 실행하는 도구다 - 이 코드베이스에
 * admin/role 개념이 전혀 없어 REST 엔드포인트로 노출하면 로그인한 아무나 전체 버킷을 조회·삭제할
 * 수 있게 되므로 그 형태는 쓰지 않는다.
 *
 * Lambda 배포는 LambdaHandler가 별도 진입점이라 main()을 거치지 않으므로, @Profile 가드가 없어도
 * 구조적으로 배포된 Lambda에는 영향이 없다 - 다만 로컬에서 프로필을 지정하지 않고 실행했을 때
 * 실수로 도는 것까지 막기 위해 가드를 둔다.
 *
 * 실행: ./gradlew bootRun --args='--spring.profiles.active=cleanup-orphans' (기본 dry-run, 보고만)
 *      ./gradlew bootRun --args='--spring.profiles.active=cleanup-orphans --delete' (실제 삭제)
 */
@Component
@Profile("cleanup-orphans")
class OrphanImageCleanupRunner(
    private val commentRepository: CommentRepository,
    private val memberRepository: MemberRepository,
    private val supabaseStorageService: SupabaseStorageService,
) : CommandLineRunner {

    override fun run(args: Array<String>) {
        val dryRun = "--delete" !in args

        val referencedUrls =
            (commentRepository.findAllContent() + memberRepository.findAllImageUrls())
                .flatMap { URL_REGEX.findAll(it).map(MatchResult::value) }
                .filter { supabaseStorageService.isManagedUrl(it) }
                .toSet()

        val bucketUrls = supabaseStorageService.listAllObjectUrls()
        val orphans = bucketUrls - referencedUrls

        println("전체 객체 ${bucketUrls.size}개, 참조됨 ${referencedUrls.size}개, 고아 후보 ${orphans.size}개")
        orphans.forEach(::println)

        if (!dryRun && orphans.isNotEmpty()) {
            supabaseStorageService.deleteObjectsByPublicUrls(orphans)
            println("${orphans.size}개 삭제 완료")
        }
    }
}
