package com.example.linksphere.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.ext.list.withNameStartingWith
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.JpaRepository

/**
 * CLAUDE.md의 "Critical Rules" / "파일 역할 규칙"을 코드로 강제한다.
 * 산문 규칙과 실제 코드가 어긋나면 여기서 잡힌다.
 *
 * 어노테이션은 이름(hasAnnotationWithName)으로 매칭한다. 이 프로젝트는 .editorconfig에서
 * 와일드카드 import를 허용하는데(ktlint_standard_no-wildcard-imports = disabled), KClass 기반 매칭
 * (hasAnnotationOf)은 와일드카드로 들어온 어노테이션의 FQN을 해석하지 못해 실제로 있는 어노테이션도
 * 없다고 오판한다 (`jakarta.persistence.*`, `org.springframework.web.bind.annotation.*` 에서 재현됨).
 */
class ArchitectureTest {

    @Test
    fun `Controller classes are annotated with RestController`() {
        Konsist.scopeFromProject()
            .classes()
            .withNameEndingWith("Controller")
            .assertTrue { it.hasAnnotationWithName("RestController") }
    }

    @Test
    fun `Controller classes do not directly reference a Repository`() {
        Konsist.scopeFromProject()
            .classes()
            .withNameEndingWith("Controller")
            .assertTrue { controller ->
                controller.properties().none { it.type?.hasNameEndingWith("Repository") == true }
            }
    }

    @Test
    fun `Service classes are annotated with Service`() {
        Konsist.scopeFromProject()
            .classes()
            .withNameEndingWith("Service")
            .assertTrue { it.hasAnnotationWithName("Service") }
    }

    @Test
    fun `Repository interfaces extend JpaRepository`() {
        Konsist.scopeFromProject()
            .interfaces()
            .withNameEndingWith("Repository")
            .assertTrue { it.hasParentOf(JpaRepository::class) }
    }

    @Test
    fun `Table entity classes are annotated with Entity`() {
        Konsist.scopeFromProject()
            .classes()
            .withNameStartingWith("Table")
            .assertTrue { it.hasAnnotationWithName("Entity") }
    }

    @Test
    fun `Repository implementations do not reference global exception classes`() {
        Konsist.scopeFromProject()
            .classes()
            .withNameEndingWith("Repository", "RepositoryImpl")
            .assertTrue { repo ->
                !repo.containingFile.hasImport { it.hasNameStartingWith("com.example.linksphere.global.exception") }
            }
    }

    @Test
    fun `every exception class in global exception package is registered in GlobalExceptionHandler`() {
        val handlerFile =
            Konsist.scopeFromProject()
                .files
                .first { it.path.endsWith("global/exception/GlobalExceptionHandler.kt") }

        Konsist.scopeFromProject()
            .classes()
            .withNameEndingWith("Exception")
            .filter { it.resideInPackage("com.example.linksphere.global.exception") }
            .assertTrue { exceptionClass ->
                handlerFile.hasTextContaining("@ExceptionHandler(${exceptionClass.name}::class)")
            }
    }
}
