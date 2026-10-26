package com.example.linksphere.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 순환을 만들어도 되는 패키지 간선 — 이 9개를 빼면 패키지 그래프에 순환이 없다(docs/BE-ARCHITECTURE.md §9).
 * LayerSkeletonRulesTest의 순환 규칙이 이 목록만 무시하고, 아래 그래프 문서 검사가 이 간선을 점선으로 그린다.
 * 간선을 더하려면 §9에 이유를 적고 여기에 추가한다.
 */
internal val DOCUMENTED_CYCLE_EDGES: List<Pair<String, String>> =
    listOf(
        // 범용 레이트리밋이 auth 도메인의 카운터 테이블을 쓴다(§3 global → domain 허용 쌍)
        "global.common" to "domain.auth",
        // 핸들러가 응답 봉투 ErrorResponse를 쓰고, RateLimitService는 RateLimitExceededException을 던진다
        "global.exception" to "global.common",
        // 커밋 뒤 디스패처가 도메인의 이벤트 DTO를 받는다(§1 패키지 역할)
        "infra.aws" to "domain.post",
        "infra.aws" to "domain.comment",
        "infra.aws" to "domain.feed",
        // MemberService가 auth의 SignupRequest·UpdateAccountRequest를 그대로 받는다
        "domain.member" to "domain.auth",
        // 글 목록 조립(PostResponseAssembler·PostRepositoryImpl)과 글 삭제가 댓글·반응·북마크를 읽고 지운다
        "domain.post" to "domain.comment",
        "domain.post" to "domain.interaction",
        // 댓글 좋아요가 댓글 존재·삭제 여부를 확인한다
        "domain.interaction" to "domain.comment",
    )

/**
 * docs/BE-ARCHITECTURE.md §1의 "실제 패키지 의존 그래프"가 코드와 같은지 본다. 그림을 손으로 그리면 새 의존이
 * 생겨도 문서가 모른 채 낡는다 — 바이트코드에서 패키지 간선을 계산해 문서의 마커 사이 블록과 비교하고, 다르면
 * 붙여 넣을 새 블록을 실패 메시지로 낸다(테스트가 문서를 고쳐 쓰지는 않는다). Gradle 테스트의 작업 디렉터리는
 * 프로젝트 루트다(HttpStatusLiteralTest와 같다).
 *
 * 패키지는 `com.example.linksphere.<계층>.<이름>`의 두 단계(`domain.post`, `global.common` 등)로 묶는다. 루트
 * (LambdaHandler 등)와 `tools/`는 두 단계가 아니라 그림·순환 검사(LayerSkeletonRulesTest) 모두에서 빠진다 — 진입점이라
 * 다른 패키지가 참조하지 않는다. `global.exception`은 모든 패키지가 예외를 던지느라 의존해 그림이 가려지므로 그림에서만
 * 뺀다(순환 검사에는 들어간다).
 */
class PackageGraphDocTest {

    private val docFile = File("docs/BE-ARCHITECTURE.md")
    private val startMarker = "<!-- package-graph:start -->"
    private val endMarker = "<!-- package-graph:end -->"
    private val hiddenPackages = setOf("global.exception")

    // 계산이 아무것도 못 찾아 빈 그림끼리 같다고 통과하는 것을 막는 하한선 - 정확한 개수가 아니다(2026-10 기준 15개).
    private val minimumPackageCount = 12

    @Test
    fun `문서의 패키지 의존 그래프가 코드와 같다`() {
        val edges = packageEdges()
        val packages = edges.flatMap { listOf(it.first, it.second) }.toSet()

        assertTrue(
            packages.size >= minimumPackageCount,
            "패키지를 최소 $minimumPackageCount 개 찾아야 하는데 ${packages.size}개만 찾았다. 임포트 범위를 확인한다.",
        )

        val expected = render(edges)
        val text = docFile.readText()
        val start = text.indexOf(startMarker)
        val end = text.indexOf(endMarker)

        assertTrue(
            start >= 0 && end > start,
            "${docFile.path}에 $startMarker … $endMarker 블록이 없다. 두 마커 사이에 아래를 넣는다:\n$expected",
        )

        val documented = text.substring(start + startMarker.length, end).trim()
        val documentedEdges = parseEdges(documented)

        assertTrue(
            documented == expected,
            buildString {
                appendLine("${docFile.path}의 패키지 그래프가 코드와 다르다.")
                (edges - documentedEdges).forEach { appendLine("  + ${it.first} → ${it.second}") }
                (documentedEdges - edges).forEach { appendLine("  - ${it.first} → ${it.second}") }
                appendLine()
                appendLine("새 의존이 의도한 것이면 $startMarker 와 $endMarker 사이를 아래로 바꾼다:")
                appendLine(expected)
            },
        )
    }

    private fun packageEdges(): Set<Pair<String, String>> {
        val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("com.example.linksphere")

        return classes.flatMap { origin ->
            val from = packageOf(origin) ?: return@flatMap emptyList()
            origin.directDependenciesFromSelf.mapNotNull { dependency ->
                val to = packageOf(dependency.targetClass)
                if (to == null || to == from) null else from to to
            }
        }.filter { (from, to) -> from !in hiddenPackages && to !in hiddenPackages }.toSet()
    }

    private fun packageOf(javaClass: JavaClass): String? {
        val segments = javaClass.packageName.removePrefix("com.example.linksphere").split(".").filter { it.isNotEmpty() }
        if (segments.size < 2 || segments.first() !in setOf("domain", "global", "infra")) {
            return null
        }

        return "${segments[0]}.${segments[1]}"
    }

    private fun render(edges: Set<Pair<String, String>>): String = buildString {
        appendLine("```mermaid")
        appendLine("flowchart LR")
        edges.flatMap { listOf(it.first, it.second) }.toSortedSet().groupBy { it.substringBefore(".") }.forEach { (layer, packages) ->
            appendLine("  subgraph $layer")
            packages.forEach { appendLine("    ${nodeId(it)}[\"${it.substringAfter(".")}\"]") }
            appendLine("  end")
        }
        edges.sortedWith(compareBy({ it.first }, { it.second })).forEach { edge ->
            val arrow = if (edge in DOCUMENTED_CYCLE_EDGES) "-.->" else "-->"
            appendLine("  ${nodeId(edge.first)} $arrow ${nodeId(edge.second)}")
        }
        append("```")
    }

    private fun nodeId(packageName: String): String = packageName.replace(".", "_")

    private fun parseEdges(block: String): Set<Pair<String, String>> = Regex("""^\s*(\w+)_(\w+) -\.?-> (\w+)_(\w+)\s*$""", RegexOption.MULTILINE)
        .findAll(block)
        .map { "${it.groupValues[1]}.${it.groupValues[2]}" to "${it.groupValues[3]}.${it.groupValues[4]}" }
        .toSet()
}
