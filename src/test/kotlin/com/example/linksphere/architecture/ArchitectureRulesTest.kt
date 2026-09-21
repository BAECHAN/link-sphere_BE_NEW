package com.example.linksphere.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SliceAssignment
import com.tngtech.archunit.library.dependencies.SliceIdentifier
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition

/**
 * PostService가 BookmarkFolderService를 참조하고 BookmarkFolderService가 다시
 * PostService를 참조하는 순환 의존이 있었는데, Spring이 빈 생성 시점에 기동을
 * 못 하는 형태로만 드러났다(PostResponseAssembler로 분리해 해결, 2026-09-21).
 * 이 클래스는 그 실패를 코드 리뷰가 아니라 CI에서 자동으로 잡기 위한 최소한의
 * 아키텍처 규칙 2개만 담는다 - 전체 레이어링을 강제하는 게 목표가 아니다.
 */
@AnalyzeClasses(
    packages = ["com.example.linksphere.domain"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class ArchitectureRulesTest {

    @ArchTest
    val controllerMustNotDependOnRepository: ArchRule =
        noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .`as`("Controller는 Repository를 직접 참조하지 않는다 - Service 레이어로 위임한다")

    // 도메인 패키지 안의 *Service 클래스만 슬라이스로 묶는다(다른 클래스는 ignore) - 도메인 사이
    // Repository를 한쪽 방향으로만 참조하는 건 이 레포에서 이미 흔한 정상 패턴이라(예: PostService가
    // interaction 도메인 Repository를 씀) 패키지 전체를 슬라이스로 잡으면 오탐이 난다. Service끼리의
    // 순환만 좁혀서 잡는다.
    private object ServiceOnlySliceAssignment : SliceAssignment {
        override fun getIdentifierOf(javaClass: JavaClass): SliceIdentifier {
            if (!javaClass.simpleName.endsWith("Service")) {
                return SliceIdentifier.ignore()
            }

            val domainPackage = javaClass.packageName
                .removePrefix("com.example.linksphere.domain.")
                .substringBefore(".")

            return SliceIdentifier.of(domainPackage)
        }

        override fun getDescription(): String = "도메인별 Service 클래스"
    }

    @ArchTest
    val servicesMustNotHaveCrossDomainCycles: ArchRule =
        SlicesRuleDefinition.slices()
            .assignedFrom(ServiceOnlySliceAssignment)
            .should().beFreeOfCycles()
            .`as`("Service 클래스는 다른 도메인의 Service와 순환 의존을 만들지 않는다")
}
