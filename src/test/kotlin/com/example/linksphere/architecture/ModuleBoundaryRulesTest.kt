package com.example.linksphere.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import jakarta.persistence.Entity
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.repository.Repository

/**
 * 다른 패키지의 저장소(Repository)와 엔티티(Table*)를 직접 쓰는 곳 — 2단계가 없애려는 것의 지금 목록이다
 * (docs/plans/2026-11-16-be-structure-stage2.md). 형식은 `쓰는 클래스 -> 쓰이는 저장소·엔티티`.
 *
 * 이 목록은 줄어들기만 한다. 새로 쓰는 곳이 생기면 아래 테스트가 실패하고, 없앤 곳을 목록에 남겨 둬도 실패한다
 * (DOCUMENTED_CYCLE_EDGES와 같은 방식). 2단계 PR마다 해당 줄을 지우고 PR-10에서 빈 목록이 된다.
 */
internal val KNOWN_BOUNDARY_VIOLATIONS: Set<String> =
    setOf(
        "domain.interaction.BookmarkFolderService -> domain.post.TablePost",
        "domain.interaction.BookmarkRepositoryCustom -> domain.post.TablePost",
        "domain.interaction.BookmarkRepositoryImpl -> domain.post.TablePost",
        "domain.interaction.BookmarkRepositoryImpl -> domain.post.TablePostView",
        "domain.interaction.TableBookmark -> domain.post.TablePost",
        "domain.post.PostRepositoryImpl -> domain.interaction.TableBookmark",
        "domain.post.PostRepositoryImpl -> domain.interaction.TablePostReaction",
        "domain.post.PostResponseAssembler -> domain.interaction.BookmarkFolderItemRepository",
        "domain.post.PostResponseAssembler -> domain.interaction.BookmarkRepository",
        "domain.post.PostResponseAssembler -> domain.interaction.PostReactionRepository",
        "domain.post.PostResponseAssembler -> domain.interaction.TableBookmark",
        "domain.post.PostResponseAssembler -> domain.interaction.TableBookmarkFolderItem",
        "domain.post.PostResponseAssembler -> domain.interaction.TablePostReaction",
        "domain.post.PostService -> domain.interaction.BookmarkFolderItemRepository",
        "domain.post.PostService -> domain.interaction.BookmarkFolderRepository",
        "domain.post.PostService -> domain.interaction.BookmarkRepository",
        "domain.post.PostService -> domain.interaction.TableBookmarkFolder",
    )

/**
 * 패키지(`domain.post`, `infra.fcm` 등 두 단계)는 다른 패키지의 저장소·엔티티를 모른다 - 그 패키지의 Service에 묻고 id와
 * 전용 객체만 받는다. 저장소와 엔티티는 테이블 구조 그대로라, 밖에서 쓰면 테이블을 바꿀 때 영향받는 곳을 그 패키지 안에서
 * 다 찾을 수 없다.
 *
 * 바이트코드의 타입 참조(필드·인자·반환 타입·호출 대상)만 본다. 두 가지는 보지 못한다. 저장소가 돌려준 엔티티를 지역 변수로만
 * 받아 다른 곳에 넘기는 코드(저장소 쪽 항목이 사라지면 함께 사라진다), 그리고 `@Query` 문자열에 글자로 적힌 다른 패키지의
 * 엔티티·테이블 이름(예전의 MemberSessionRepository·FcmTokenRepository - 지금은 없고, 계획 PR-10에서 따로 검사한다). 루트(LambdaHandler)와 `tools/`는
 * PackageGraphDocTest와 같은 이유로 빠진다 - `tools/`는 로컬에서 한 번 돌리는 도구다.
 */
class ModuleBoundaryRulesTest {

    // 계산이 아무것도 못 찾아 "위반 없음"으로 통과하는 것을 막는다 - 저장소·엔티티를 이만큼은 찾아야 한다(2026-11 기준 40개 이상)
    private val minimumPersistenceClassCount = 20

    @Test
    fun `다른 패키지의 저장소·엔티티를 새로 쓰지 않는다`() {
        val added = violations() - KNOWN_BOUNDARY_VIOLATIONS

        assertTrue(
            added.isEmpty(),
            buildString {
                appendLine("다른 패키지의 저장소·엔티티를 직접 쓰는 곳이 새로 생겼다. 그 패키지의 Service에 묻고 id·전용 객체를 받도록 바꾼다:")
                added.sorted().forEach { appendLine("  + $it") }
            },
        )
    }

    @Test
    fun `알려진 위반 목록의 항목은 모두 실제로 있다`() {
        val stale = KNOWN_BOUNDARY_VIOLATIONS - violations()

        assertTrue(stale.isEmpty(), "KNOWN_BOUNDARY_VIOLATIONS에 이미 없앤 항목이 남아 있다 - 목록에서 지운다:\n${stale.sorted().joinToString("\n") { "  - $it" }}")
    }

    private fun violations(): Set<String> {
        val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("com.example.linksphere")
        val persistenceClasses = classes.filter { isPersistence(it) }

        assertTrue(
            persistenceClasses.size >= minimumPersistenceClassCount,
            "저장소·엔티티를 최소 $minimumPersistenceClassCount 개 찾아야 하는데 ${persistenceClasses.size}개만 찾았다. 임포트 범위를 확인한다.",
        )

        return classes.flatMap { origin ->
            val from = packageOf(origin) ?: return@flatMap emptyList()
            origin.directDependenciesFromSelf.mapNotNull { dependency ->
                val target = dependency.targetClass
                val to = packageOf(target)
                if (to == null || to == from || !isPersistence(target)) null else "${label(origin)} -> ${label(target)}"
            }
        }.toSet()
    }

    private fun isPersistence(javaClass: JavaClass): Boolean = javaClass.isAnnotatedWith(Entity::class.java) ||
        javaClass.isAssignableTo(Repository::class.java) ||
        javaClass.simpleName.endsWith("RepositoryImpl") ||
        javaClass.simpleName.endsWith("RepositoryCustom")

    // Kotlin은 람다·companion을 바깥 클래스와 다른 클래스로 만든다 - 목록에는 바깥 클래스 이름으로 적는다
    private fun label(javaClass: JavaClass): String {
        val outermost = generateSequence(javaClass) { it.enclosingClass.orElse(null) }.last()
        return "${packageOf(outermost)}.${outermost.simpleName}"
    }

    private fun packageOf(javaClass: JavaClass): String? {
        val segments = javaClass.packageName.removePrefix("com.example.linksphere").split(".").filter { it.isNotEmpty() }
        if (segments.size < 2 || segments.first() !in setOf("domain", "global", "infra")) {
            return null
        }

        return "${segments[0]}.${segments[1]}"
    }
}
