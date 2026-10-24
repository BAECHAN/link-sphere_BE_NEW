package com.example.linksphere.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 응답 상태코드를 숫자 리터럴로 쓰지 못하게 한다(BE CLAUDE.md Critical Rules "HTTP 상태코드 하드코딩 금지") -
 * `ApiResponse(201, …)`처럼 쓰면 같은 화면의 다른 엔드포인트와 값이 어긋나도 눈에 띄지 않는다. 상수 인자는
 * 바이트코드에서 리터럴과 `HttpStatus.X.value()`가 구별되지 않아 ArchUnit으로는 잡을 수 없어서, 소스 텍스트를 본다.
 * Gradle 테스트의 작업 디렉터리는 프로젝트 루트다.
 */
class HttpStatusLiteralTest {

    private val sourceRoot = File("src/main/kotlin")

    // 스캔이 아무것도 못 읽어 조용히 통과하는 것을 막는 하한선 - 정확한 개수가 아니다(2026-10 기준 140여 개).
    private val minimumFileCount = 100

    private val literalPatterns =
        listOf(
            Regex("""(ApiResponse|ErrorResponse)\(\s*(status\s*=\s*)?\d{3}"""),
            Regex("""ResponseEntity\s*\.\s*status\(\s*\d{3}"""),
        )

    @Test
    fun `응답 상태코드에 숫자 리터럴을 쓰지 않는다`() {
        val files = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        assertTrue(
            files.size >= minimumFileCount,
            "Kotlin 소스를 최소 $minimumFileCount 개 읽어야 하는데 ${files.size}개만 읽었다. " +
                "작업 디렉터리(${File("").absolutePath})와 sourceRoot($sourceRoot)를 확인한다.",
        )

        val violations =
            files.sortedBy { it.path }.flatMap { file ->
                val text = file.readText()
                literalPatterns.flatMap { pattern ->
                    pattern.findAll(text).map { match ->
                        val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                        "${file.relativeTo(sourceRoot)}:$line: ${match.value}"
                    }
                }
            }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("상태코드를 숫자로 쓴 곳이 ${violations.size}건 있다 - HttpStatus.X.value()(ResponseEntity는 HttpStatus.X)로 바꾼다:")
                violations.forEach { appendLine("  - $it") }
            },
        )
    }
}
