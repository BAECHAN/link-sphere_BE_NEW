package com.example.linksphere.architecture

import com.example.linksphere.domain.auth.AuthRateLimitRepository
import com.example.linksphere.domain.auth.SessionAuthenticationFilter
import com.example.linksphere.domain.auth.TableAuthRateLimit
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.config.SecurityConfig
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaAccess.Predicates.origin
import com.tngtech.archunit.core.domain.JavaAccess.Predicates.target
import com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo
import com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.domain.properties.HasModifiers.Predicates.modifier
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import org.springframework.data.repository.Repository
import org.springframework.web.bind.annotation.RestController

/**
 * 앱 전체(domain·global·infra)에 걸리는 레이어 규칙. ArchitectureRulesTest는 domain 패키지만 분석해
 * infra의 FcmTokenController 같은 곳을 보지 못하는데, 그쪽 분석 범위를 넓히면 ServiceOnlySliceAssignment가
 * global·infra의 Service까지 한 슬라이스로 묶어 순환 판정이 틀어진다. 그래서 RepositoryDeleteRulesTest처럼
 * 별도 클래스로 앱 전체를 분석한다. 각 규칙의 배경은 docs/BE-ARCHITECTURE.md §2·§3.
 *
 * ArchUnit은 that() 절에 걸리는 클래스가 하나도 없으면 규칙을 실패시킨다(archRule.failOnEmptyShould 기본값) -
 * 패키지가 바뀌어 대상이 사라져도 조용히 통과하지 않는다.
 */
@AnalyzeClasses(
    packages = ["com.example.linksphere"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class AppWideArchitectureRulesTest {

    @ArchTest
    val controllersMustNotDependOnRepositories: ArchRule =
        noClasses()
            .that().areAnnotatedWith(RestController::class.java)
            .should().dependOnClassesThat(
                assignableTo(Repository::class.java).or(simpleNameEndingWith("RepositoryImpl")),
            )
            .`as`("@RestController는 Repository를 직접 참조하지 않는다 - Service 레이어로 위임한다")

    // 예외는 Service가 던진다(BE CLAUDE.md Critical Rules). Kotlin이 기본 인자용으로 만드는 synthetic
    // `$default` 메서드는 UnsupportedOperationException을 생성하므로 synthetic 출처는 뺀다. 람다 본문은
    // synthetic이 아니라 그대로 검사된다.
    @ArchTest
    val repositoriesMustNotConstructExceptions: ArchRule =
        noClasses()
            .that().areAssignableTo(Repository::class.java)
            .or().haveSimpleNameEndingWith("RepositoryImpl")
            .should().callConstructorWhere(
                target(owner(assignableTo(Throwable::class.java)))
                    .and(origin(not(modifier(JavaModifier.SYNTHETIC)))),
            )
            .`as`("Repository는 예외를 만들어 던지지 않는다 - 없으면 null/0을 돌려주고 Service가 판단한다")

    // GlobalExceptionHandler가 IllegalArgumentException을 라이브러리발 입력 오류(400)로만 다루므로, 앱 코드가 직접
    // 던지면 "없음"·"불변조건 위반"이 입력 오류로 섞인다. 입력 오류는 InvalidInputException, 없음은 *NotFoundException,
    // 내부 불변조건은 IllegalStateException을 쓴다(docs/BE-ARCHITECTURE.md §4). require() 같은 Kotlin inline 함수도
    // 호출부 바이트코드에 IAE 생성이 펼쳐져 함께 잡힌다 - 정책상 맞다.
    @ArchTest
    val appCodeMustNotConstructIllegalArgumentException: ArchRule =
        noClasses()
            .should().callConstructorWhere(target(owner(assignableTo(IllegalArgumentException::class.java))))
            .`as`("앱 코드는 IllegalArgumentException을 만들지 않는다 - 의미 있는 예외(§4)를 던진다")

    // global은 도메인을 모르는 공통 계층이다. 아래 세 쌍만 의도적인 예외다(docs/BE-ARCHITECTURE.md §3):
    // 보안 필터 체인이 auth 도메인의 세션 필터를 끼우고, 범용 레이트리밋이 auth 도메인의 카운터 테이블을 쓴다.
    @ArchTest
    val globalMustNotDependOnDomain: ArchRule =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Global").definedBy("com.example.linksphere.global..")
            .layer("Domain").definedBy("com.example.linksphere.domain..")
            .whereLayer("Global").mayNotAccessAnyLayer()
            .ignoreDependency(SecurityConfig::class.java, SessionAuthenticationFilter::class.java)
            .ignoreDependency(RateLimitService::class.java, AuthRateLimitRepository::class.java)
            .ignoreDependency(RateLimitService::class.java, TableAuthRateLimit::class.java)
            .`as`("global은 domain에 의존하지 않는다 - 허용된 세 쌍만 예외")
}
