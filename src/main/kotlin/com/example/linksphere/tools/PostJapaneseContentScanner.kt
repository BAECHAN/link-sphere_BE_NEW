package com.example.linksphere.tools

import com.example.linksphere.domain.post.PostRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 도쿄 리전 IP 오염(§5.9)으로 title·description·aiSummary가 일본어로 저장된 게시글이
 * `PostLocaleBackfillRunner`로 고친 2건 말고 더 있는지 전수조사하는 읽기 전용 도구.
 *
 * 그 2건은 사용자가 직접 발견해 보고한 것이지 전수조사 결과가 아니었다 - `LOCALE_OVERRIDE_HOSTS`에
 * 등록되지 않은 다른 사이트가 같은 엣지 레벨 언어 라우팅을 쓴다면 여전히 일본어로 남아있는
 * 게시글이 있을 수 있다. 히라가나·가타카나(U+3040–U+30FF)는 한국어 텍스트에 절대 섞이지
 * 않으므로 오탐 없이 판정할 수 있다 - 한자만으로는(한국어 한자 병기와 겹쳐) 판정하지 않는다.
 *
 * 다른 backfill 도구와 달리 DB를 쓰지 않는 순수 조회라 dry-run/--commit 구분이 없다.
 *
 * 실행: ./gradlew bootRun --args='--spring.profiles.active=secret,post-japanese-scan'
 */
@Component
@Profile("post-japanese-scan")
class PostJapaneseContentScanner(
    private val postRepository: PostRepository,
) : CommandLineRunner {

    private val hiraganaKatakana = Regex("[぀-ヿ]")

    override fun run(args: Array<String>) {
        val posts = postRepository.findAll()
        val hits =
            posts.filter { post ->
                hiraganaKatakana.containsMatchIn(post.title) ||
                    hiraganaKatakana.containsMatchIn(post.description.orEmpty()) ||
                    hiraganaKatakana.containsMatchIn(post.aiSummary.orEmpty())
            }

        println("전체 ${posts.size}건 중 일본어 문자(히라가나/가타카나) 포함 ${hits.size}건")
        hits.forEach { post ->
            val field =
                listOfNotNull(
                    "title".takeIf { hiraganaKatakana.containsMatchIn(post.title) },
                    "description".takeIf { hiraganaKatakana.containsMatchIn(post.description.orEmpty()) },
                    "aiSummary".takeIf { hiraganaKatakana.containsMatchIn(post.aiSummary.orEmpty()) },
                ).joinToString("+")
            println("  ${post.id} | $field | ${post.url}")
        }
    }
}
