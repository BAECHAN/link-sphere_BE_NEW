package com.example.linksphere.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import jakarta.persistence.CollectionTable
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

/**
 * 다른 패키지의 저장소(Repository)와 엔티티(Table*)를 직접 쓰는 곳 — 2단계가 없애려는 것의 지금 목록이다
 * (docs/plans/2026-11-16-be-structure-stage2.md). 형식은 `쓰는 클래스 -> 쓰이는 저장소·엔티티`.
 *
 * 이 목록은 줄어들기만 한다. 새로 쓰는 곳이 생기면 아래 테스트가 실패하고, 없앤 곳을 목록에 남겨 둬도 실패한다
 * (DOCUMENTED_CYCLE_EDGES와 같은 방식). 2단계 PR마다 해당 줄을 지워 PR-9c에서 빈 목록이 됐다 - 지금은 "새로 쓰지 않는다" 규칙만 남았다.
 */
internal val KNOWN_BOUNDARY_VIOLATIONS: Set<String> = emptySet()

/**
 * 패키지(`domain.post`, `infra.fcm` 등 두 단계)는 다른 패키지의 저장소·엔티티를 모른다 - 그 패키지의 Service에 묻고 id와
 * 전용 객체만 받는다. 저장소와 엔티티는 테이블 구조 그대로라, 밖에서 쓰면 테이블을 바꿀 때 영향받는 곳을 그 패키지 안에서
 * 다 찾을 수 없다.
 *
 * 첫 검사는 바이트코드의 타입 참조(필드·인자·반환 타입·호출 대상)를 본다. 저장소가 돌려준 엔티티를 지역 변수로만 받아 다른 곳에
 * 넘기는 코드는 보지 못한다(저장소 쪽 항목이 사라지면 함께 사라진다). `@Query` 문자열에 글자로 적힌 다른 패키지의 엔티티·테이블
 * 이름(예전의 MemberSessionRepository·FcmTokenRepository)은 타입 참조가 아니라서 세 번째 검사가 문자열을 읽어 따로 본다(SQL은 대소문자를
 * 가리지 않으니 이름도 가리지 않고 찾는다). `@Query`가 아닌 곳에 적은 SQL 문자열(createNativeQuery), 이름으로 부르는 쿼리,
 * `@JoinTable`·`@SecondaryTable`·상속받은 필드의 테이블 이름은 보지 못한다 - 지금 코드에는 없다. 루트(LambdaHandler)와 `tools/`는 PackageGraphDocTest와 같은 이유로
 * 빠진다 - `tools/`는 로컬에서 한 번 돌리는 도구다.
 */
class ModuleBoundaryRulesTest {

    // 계산이 아무것도 못 찾아 "위반 없음"으로 통과하는 것을 막는다 - 저장소·엔티티를 이만큼은 찾아야 한다(2026-11 기준 40개 이상)
    private val minimumPersistenceClassCount = 20

    // 같은 이유로 `@Query` 문장도 이만큼은 찾아야 한다(2026-11 기준 70개 이상)
    private val minimumQueryCount = 40

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

    @Test
    fun `@Query 문자열에 다른 패키지의 엔티티·테이블 이름을 적지 않는다`() {
        val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("com.example.linksphere")
        // 이름 -> 그 엔티티·테이블을 가진 패키지. JPQL은 엔티티 이름(TablePost), 네이티브 SQL은 테이블 이름(posts·post_categories)으로 적는다
        val owners =
            classes.filter { it.isAnnotatedWith(Entity::class.java) }.flatMap { entity ->
                val owner = packageOf(entity) ?: return@flatMap emptyList()
                val tables = listOfNotNull(entity.tryGetAnnotationOfType(Table::class.java).orElse(null)?.name)
                val collectionTables = entity.fields.mapNotNull { it.tryGetAnnotationOfType(CollectionTable::class.java).orElse(null)?.name }

                (listOf(entity.simpleName) + tables + collectionTables).filter { it.isNotBlank() }.map { it to owner }
            }.toMap()
        val queries =
            classes.flatMap { repository ->
                val from = packageOf(repository) ?: return@flatMap emptyList()
                repository.methods.mapNotNull { method ->
                    method.tryGetAnnotationOfType(Query::class.java).orElse(null)?.let { Triple(from, "${repository.simpleName}.${method.name}", "${it.value}\n${it.countQuery}") }
                }
            }

        assertTrue(queries.size >= minimumQueryCount, "`@Query` 문장을 최소 $minimumQueryCount 개 찾아야 하는데 ${queries.size}개만 찾았다. 임포트 범위를 확인한다.")
        assertTrue(owners.size >= minimumPersistenceClassCount, "엔티티·테이블 이름을 최소 $minimumPersistenceClassCount 개 찾아야 하는데 ${owners.size}개만 찾았다.")

        // 연결 테이블 이름을 읽는 길이 깨지면 위 개수 기준으로는 드러나지 않는다 - 지금 있는 하나를 이름으로 확인한다
        assertTrue(owners["post_categories"] == "domain.post", "@CollectionTable의 연결 테이블 이름(post_categories)을 찾지 못했다.")

        val found =
            queries.flatMap { (from, where, text) ->
                owners.filter { (name, owner) -> owner != from && Regex("(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE).containsMatchIn(text) }
                    .map { (name, owner) -> "$where -> $name($owner)" }
            }

        assertTrue(
            found.isEmpty(),
            buildString {
                appendLine("`@Query` 문자열이 다른 패키지의 엔티티·테이블을 직접 읽는다. 그 패키지의 Service에 id를 물어 받아 쓰도록 바꾼다:")
                found.sorted().forEach { appendLine("  + $it") }
            },
        )
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
