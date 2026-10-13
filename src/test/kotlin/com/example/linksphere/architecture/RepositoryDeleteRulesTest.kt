package com.example.linksphere.architecture

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

/**
 * Spring Data의 derived delete(`deleteBy…`)는 엔티티를 읽어 하나씩 지우고 Hibernate가 지운 행 수를
 * 검사해서, 같은 행을 동시에 지운 요청과 부딪히면 StaleObjectStateException(HTTP면 409, 비동기 작업이면
 * 롤백)을 낸다. 2026-10-13 좋아요 연타 500이 이 경로였다. 삭제 메서드는 일괄 DELETE(`@Modifying @Query`)로만
 * 만들게 강제한다. ArchitectureRulesTest와 달리 infra(FCM 등)까지 보려고 앱 전체를 분석한다.
 */
@AnalyzeClasses(
    packages = ["com.example.linksphere"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class RepositoryDeleteRulesTest {

    @ArchTest
    val repositoryDeleteMethodsMustBeBulk: ArchRule =
        methods()
            .that().haveNameMatching("(delete|remove)([A-Z].*)?By.*")
            .and().areDeclaredInClassesThat().areInterfaces()
            .and().areDeclaredInClassesThat().areAssignableTo(Repository::class.java)
            .should().beAnnotatedWith(Modifying::class.java)
            .andShould().beAnnotatedWith(Query::class.java)
            .`as`("리포지토리의 delete/remove 메서드는 derived delete가 아니라 @Modifying @Query 일괄 DELETE로 만든다")
}
