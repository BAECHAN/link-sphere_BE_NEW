package com.example.linksphere.global.common

import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Order
import jakarta.persistence.criteria.Predicate

// 정렬 키 하나. 목록의 마지막 키는 동점을 끊는 고유 값이어야 한다
class SeekKey(val expression: Expression<*>, val descending: Boolean = true) {
    @Suppress("UNCHECKED_CAST")
    private val comparable = expression as Expression<Comparable<Any>>

    @Suppress("UNCHECKED_CAST")
    fun beyond(cb: CriteriaBuilder, value: Comparable<*>): Predicate = (value as Comparable<Any>).let {
        if (descending) cb.lessThan(comparable, it) else cb.greaterThan(comparable, it)
    }

    @Suppress("UNCHECKED_CAST")
    fun notBefore(cb: CriteriaBuilder, value: Comparable<*>): Predicate = (value as Comparable<Any>).let {
        if (descending) cb.lessThanOrEqualTo(comparable, it) else cb.greaterThanOrEqualTo(comparable, it)
    }

    fun order(cb: CriteriaBuilder): Order = if (descending) cb.desc(expression) else cb.asc(expression)
}

/**
 * "이 키 값들 다음 행부터"를 조건식으로 만든다. 방향이 섞인 키(제목 오름차순 + 시각 내림차순)도 된다.
 *
 * `(k1, k2) < (x, y)` 행 비교를 쓰지 않는 이유: Hibernate 6.6.53의 tuple()은 JpaCompoundSelection을 돌려줘 lessThan에
 * 넣을 수 없다(javap 확인). 그래서 `k1 <= x AND (k1 < x OR (k1 = x AND k2 < y))`로 펼친다. 앞의 상한은 논리적으로 없어도
 * 되지만, 없으면 Postgres가 인덱스를 처음부터 훑어 100만 행 끝 부근에서 192ms가 걸린다(상한이 있으면 0.12ms, 직접 측정, docs/PAGINATION.md §9.1).
 */
object SeekPredicate {
    fun after(cb: CriteriaBuilder, keys: List<SeekKey>, values: List<Comparable<*>>): Predicate {
        val pairs = keys.zip(values)
        val (lastKey, lastValue) = pairs.last()
        val expanded =
            pairs.dropLast(1).foldRight(lastKey.beyond(cb, lastValue)) { (key, value), rest ->
                cb.or(key.beyond(cb, value), cb.and(cb.equal(key.expression, value), rest))
            }
        val (firstKey, firstValue) = pairs.first()

        return cb.and(firstKey.notBefore(cb, firstValue), expanded)
    }
}
