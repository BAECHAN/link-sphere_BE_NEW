package com.example.linksphere.global.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException

class IdempotentCreateTest {

    private val index = "uq_test_index"

    private fun violation(constraint: String) = DataIntegrityViolationException("x", RuntimeException("""duplicate key value violates unique constraint "$constraint""""))

    @Test
    fun `run은 이미 있으면 만들지 않는다`() {
        val result = IdempotentCreate.run(index, find = { "existing" }, create = { error("만들면 안 된다") })

        assertEquals("existing", result)
    }

    @Test
    fun `run은 없으면 만든다`() {
        assertEquals("created", IdempotentCreate.run<String>(index, find = { null }, create = { "created" }))
    }

    @Test
    fun `run은 우리 인덱스 위반이면 다시 조회해 이긴 쪽 결과를 돌려준다`() {
        var finds = 0

        val result = IdempotentCreate.run(
            index,
            find = { if (finds++ == 0) null else "winner" },
            create = { throw violation(index) },
        )

        assertEquals("winner", result)
    }

    @Test
    fun `run은 다른 제약 위반이면 그대로 던진다`() {
        val violation = violation("fk_other")

        val thrown = assertThrows(DataIntegrityViolationException::class.java) {
            IdempotentCreate.run<String>(index, find = { null }, create = { throw violation })
        }

        assertEquals(violation, thrown)
    }

    // 이긴 쪽이 커밋 뒤 바로 지워진 경우처럼 재조회도 비면 없는 결과를 지어낼 수 없다
    @Test
    fun `run은 위반 뒤 재조회도 비면 원래 예외를 던진다`() {
        val violation = violation(index)

        val thrown = assertThrows(DataIntegrityViolationException::class.java) {
            IdempotentCreate.run<String>(index, find = { null }, create = { throw violation })
        }

        assertEquals(violation, thrown)
    }

    @Test
    fun `ignoringLoss는 우리 인덱스 위반만 삼킨다`() {
        IdempotentCreate.ignoringLoss(index) { throw violation(index) }

        val other = violation("fk_other")
        val thrown = assertThrows(DataIntegrityViolationException::class.java) {
            IdempotentCreate.ignoringLoss(index) { throw other }
        }

        assertEquals(other, thrown)
    }
}
