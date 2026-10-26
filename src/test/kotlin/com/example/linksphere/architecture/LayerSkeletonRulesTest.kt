package com.example.linksphere.architecture

import com.example.linksphere.domain.category.TableCategory
import com.example.linksphere.domain.member.EmailVerificationGate
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.ErrorResponse
import com.example.linksphere.global.common.TransactionCallbacks
import com.example.linksphere.global.exception.EmailNotVerifiedException
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.base.DescribedPredicate.describe
import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.conditions.ArchConditions.be
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.servlet.Filter
import org.springframework.data.repository.Repository
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.bind.annotation.RestController

/**
 * Controller·Service·Repository·엔티티·DTO가 서로 무엇을 참조해도 되는지(docs/BE-ARCHITECTURE.md §3 "레이어 골격")를
 * 강제한다. 2026-10-26 골격 점검에서 모두 위반 0건으로 확인한 방향이라, 새 코드가 처음 어기는 순간 실패한다.
 * AppWideArchitectureRulesTest와 같은 이유로 앱 전체를 분석한다.
 *
 * Kotlin은 companion object·람다를 바깥 클래스와 다른 클래스(`PostController$Companion`)로 만든다 — 그래서 역할은
 * 어노테이션뿐 아니라 바깥 클래스 이름으로도 판정한다.
 */
@AnalyzeClasses(
    packages = ["com.example.linksphere"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class LayerSkeletonRulesTest {

    @ArchTest
    val controllersMustNotDependOnEntities: ArchRule =
        noClasses()
            .that().areAnnotatedWith(RestController::class.java)
            .should().dependOnClassesThat().areAnnotatedWith(Entity::class.java)
            .`as`("@RestController는 엔티티(Table*)를 모른다 - Service가 응답 DTO로 바꿔 돌려준다")

    // 컨트롤러는 맨 위 계층이라 아무도 참조하지 않는다 - 컨트롤러의 상수·헬퍼가 필요해지면 Service나 companion이 아닌 곳으로 옮긴다.
    @ArchTest
    val nothingDependsOnControllers: ArchRule =
        noClasses()
            .that(not(controllerOrFilter))
            .should().dependOnClassesThat().areAnnotatedWith(RestController::class.java)
            .`as`("컨트롤러 밖의 코드는 @RestController를 참조하지 않는다")

    // 웹 입출력 타입은 컨트롤러·보안 필터·GlobalExceptionHandler(global)의 것이다. 그 아래가 서블릿 요청이나 응답 래퍼를
    // 알면 같은 로직을 잡(linksphereJob)·봇 경로에서 쓸 수 없다. 외부 API를 부르는 RestClient·RestTemplate은 여기 들지 않는다.
    @ArchTest
    val domainCodeMustNotDependOnWebTypes: ArchRule =
        noClasses()
            .that().resideInAnyPackage("com.example.linksphere.domain..", "com.example.linksphere.infra..")
            .and(not(controllerOrFilter))
            .should().dependOnClassesThat(
                resideInAPackage("jakarta.servlet..")
                    .or(resideInAPackage("org.springframework.web.bind.annotation.."))
                    .or(assignableTo(ResponseEntity::class.java))
                    .or(assignableTo(ApiResponse::class.java))
                    .or(assignableTo(ErrorResponse::class.java)),
            )
            .`as`("컨트롤러·필터 밖의 domain·infra 코드는 서블릿·ResponseEntity·ApiResponse를 참조하지 않는다")

    @ArchTest
    val persistenceMustNotDependOnUpperLayers: ArchRule =
        noClasses()
            .that(repositoryLike.or(annotated(Entity::class.java)))
            .should().dependOnClassesThat(
                annotated(Service::class.java)
                    .or(annotated(Component::class.java))
                    .or(annotated(RestController::class.java))
                    .or(declaredInDtoFile),
            )
            .`as`("Repository·엔티티는 Service·Component·Controller·DTO를 참조하지 않는다 - 아래 계층은 위를 모른다")

    @ArchTest
    val dtosMustNotDependOnBeans: ArchRule =
        noClasses()
            .that(declaredInDtoFile)
            .should().dependOnClassesThat(
                annotated(Service::class.java)
                    .or(annotated(Component::class.java))
                    .or(annotated(RestController::class.java))
                    .or(repositoryLike),
            )
            .`as`("DTO는 데이터만 담는다 - Service·Component·Repository·Controller를 참조하지 않는다")

    @ArchTest
    val apiDtosLiveInDtoFiles: ArchRule =
        classes()
            .that(
                resideInAPackage("com.example.linksphere.domain..")
                    .and(simpleNameEndingWith("Request").or(simpleNameEndingWith("Response"))),
            )
            .should(be(declaredInDtoFile))
            .`as`("domain의 *Request·*Response는 그 도메인의 *DTO.kt에 둔다")

    // 패키지(domain.post, global.common …) 사이 순환을 막는다. 지금 있는 순환은 DOCUMENTED_CYCLE_EDGES의 8개 간선뿐이고
    // (PackageGraphDocTest.kt, 이유는 BE-ARCHITECTURE §9), 그 간선만 무시하면 순환이 없다. 이 규칙은 그 밖의 새 순환을
    // 막는다 — 기존 servicesMustNotHaveCrossDomainCycles는 *Service끼리만 묶어 Assembler·Repository를 거친 순환을 보지 못한다.
    @ArchTest
    val packagesMustBeFreeOfCyclesExceptDocumented: ArchRule =
        DOCUMENTED_CYCLE_EDGES.fold(
            slices().matching("com.example.linksphere.(*).(*)..").should().beFreeOfCycles(),
        ) { rule, (from, to) ->
            rule.ignoreDependency(
                resideInAPackage("com.example.linksphere.$from.."),
                resideInAPackage("com.example.linksphere.$to.."),
            )
        }.`as`("패키지 사이에 문서화된 8개 간선 말고는 순환을 만들지 않는다")

    // 커밋 뒤 부수 효과(파일 삭제·메일)는 TransactionCallbacks.afterCommit으로만 등록한다 - 복사본 5곳을 모은 헬퍼다(§7-6)
    @ArchTest
    val afterCommitGoesThroughTransactionCallbacks: ArchRule =
        noClasses()
            .that().doNotBelongToAnyOf(TransactionCallbacks::class.java)
            .should().callMethod(TransactionSynchronizationManager::class.java, "registerSynchronization", TransactionSynchronization::class.java)
            .`as`("커밋 뒤 작업은 TransactionCallbacks.afterCommit으로 등록한다")

    // 글쓰기·댓글쓰기·링크 미리보기의 이메일 인증 확인은 EmailVerificationGate 하나로 한다 - 인라인 4곳을 모은 헬퍼다
    @ArchTest
    val emailVerificationGoesThroughGate: ArchRule =
        noClasses()
            .that().doNotBelongToAnyOf(EmailVerificationGate::class.java)
            .should().callConstructor(EmailNotVerifiedException::class.java, String::class.java)
            .`as`("EmailNotVerifiedException은 EmailVerificationGate에서만 만든다")

    // 새 엔티티 PK는 앱에서 만든 UUID다(§7-13). @GeneratedValue는 예전부터 쓰던 세 엔티티에만 남긴다 - save()가 INSERT를
    // 커밋까지 미뤄 제약 위반을 늦게 알게 되는 함정이 있었다(MemberServiceIntegrationTest)
    @ArchTest
    val generatedIdsOnlyOnLegacyEntities: ArchRule =
        fields()
            .that().areAnnotatedWith(GeneratedValue::class.java)
            .should().beDeclaredInClassesThat().belongToAnyOf(TableMember::class.java, TablePost::class.java, TableCategory::class.java)
            .`as`("새 엔티티에는 @GeneratedValue를 쓰지 않는다 - 앱에서 UUID를 만든다")

    private companion object {
        val repositoryLike: DescribedPredicate<JavaClass> =
            assignableTo(Repository::class.java)
                .or(simpleNameEndingWith("RepositoryImpl"))
                .or(simpleNameEndingWith("RepositoryCustom"))

        val declaredInDtoFile: DescribedPredicate<JavaClass> =
            describe("declared in *DTO.kt / *Dtos.kt") { javaClass ->
                javaClass.source.flatMap { it.fileName }.map { it.endsWith("DTO.kt") || it.endsWith("Dtos.kt") }.orElse(false)
            }

        val controllerOrFilter: DescribedPredicate<JavaClass> =
            describe("@RestController·servlet Filter와 그 내부 클래스") { javaClass ->
                generateSequence(javaClass) { it.enclosingClass.orElse(null) }.any {
                    it.isAnnotatedWith(RestController::class.java) || it.isAssignableTo(Filter::class.java)
                }
            }

        // annotatedWith는 CanBeAnnotated 술어라 JavaClass 술어(declaredInDtoFile 등)와 or로 잇기 위해 타입을 좁힌다
        fun annotated(annotation: Class<out Annotation>): DescribedPredicate<JavaClass> = annotatedWith(annotation).forSubtype()
    }
}
