package com.example.linksphere.tools

import com.example.linksphere.domain.upload.OrphanImageGcService
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 고아 이미지 정리(OrphanImageGcService)를 로컬에서 손으로 돌려 후보를 눈으로 확인하는 도구다.
 * 운영에서는 같은 서비스가 EventBridge cron(LambdaHandler "orphan-image-gc")으로 4일마다 돈다 -
 * 이 도구는 그 전에 결과를 미리 보거나, 정기 실행을 기다리지 않고 한 번 돌릴 때 쓴다. 이 코드베이스에
 * admin/role 개념이 없어 REST 엔드포인트로 노출하면 로그인한 아무나 버킷을 지울 수 있게 되므로 그
 * 형태는 쓰지 않는다.
 *
 * Lambda 배포는 LambdaHandler가 별도 진입점이라 main()을 거치지 않으므로, @Profile 가드가 없어도
 * 구조적으로 배포된 Lambda에는 영향이 없다 - 다만 로컬에서 프로필을 지정하지 않고 실행했을 때
 * 실수로 도는 것까지 막기 위해 가드를 둔다.
 *
 * 실행: ./gradlew bootRun --args='--spring.profiles.active=secret,cleanup-orphans' (기본 dry-run, 보고만)
 *      ./gradlew bootRun --args='--spring.profiles.active=secret,cleanup-orphans --delete' (실제 삭제)
 * secret을 함께 넣어야 한다 - profiles.active를 덮어쓰면 기본값 secret이 빠져 DB 설정이 안 읽힌다.
 */
@Component
@Profile("cleanup-orphans")
class OrphanImageCleanupRunner(
    private val orphanImageGcService: OrphanImageGcService,
) : CommandLineRunner {

    override fun run(args: Array<String>) {
        val summary = orphanImageGcService.collect(dryRun = "--delete" !in args)

        println(
            "전체 객체 ${summary.total}개, 참조됨 ${summary.referenced}개, " +
                "고아 후보 ${summary.candidates.size}개(${OrphanImageGcService.MIN_AGE.toHours()}시간 지난 것만)",
        )
        summary.candidates.forEach(::println)

        if (summary.aborted) {
            println("참조가 0건이라 안전을 위해 삭제하지 않았다")
        } else if (summary.deleteRequested > 0) {
            println("${summary.deleteRequested}개 삭제 요청 완료(한 번에 최대 ${OrphanImageGcService.MAX_DELETE_PER_RUN}개)")
        }
    }
}
