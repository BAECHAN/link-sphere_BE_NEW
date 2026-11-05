package com.example.linksphere.global.common

import jakarta.persistence.EntityManager
import jakarta.persistence.Tuple
import jakarta.persistence.criteria.AbstractQuery
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Predicate
import org.hibernate.query.criteria.HibernateCriteriaBuilder
import org.hibernate.query.criteria.JpaSubQuery

data class KeysetSlice<T>(
    val content: List<T>,
    // 다음 쪽이 있을 때만 채운다 - 이 쪽 마지막 행의 정렬 키 값
    val nextKeys: List<Comparable<*>>? = null,
    // 건수를 요청했을 때만 채운다
    val total: Long? = null,
)

// 한 목록의 정렬 키와 후보 조건. 쿼리 객체를 받아 그 안에서 만들어야 하므로 함수로 넘겨받는다
class KeysetSpec(val keys: List<SeekKey>, val predicates: List<Predicate>)

/**
 * cursor 목록의 Criteria 조회. 키 값만 먼저 읽고(마지막 키가 id), 엔티티는 slice()가 id로 불러온다. 파생 테이블은 엔티티를 돌려줄 수 없어
 * 두 형태 모두 같은 방식으로 맞췄다. 쪽마다 2번 조회하지만, offset도 count + 본 쿼리로 2번이었다.
 */
object KeysetQuery {
    /**
     * 조건을 WHERE에 바로 쓴다. 첫 키에 인덱스가 있으면(최신순·오래된순) 인덱스로 시작 지점을 찾고, 첫 키가 조인할 표를 읽지 않는 싼 식이면
     * (최근 열람순) 조인 전에 행을 걸러 조인할 행이 준다(직접 측정, docs/PAGINATION.md §9.8).
     */
    fun directRows(entityManager: EntityManager, after: List<Comparable<*>>?, limit: Int, spec: (AbstractQuery<*>) -> KeysetSpec): List<List<Comparable<*>>> {
        val cb = entityManager.criteriaBuilder
        val query = cb.createTupleQuery()
        val built = spec(query)
        val seek = after?.let { listOf(SeekPredicate.after(cb, built.keys, it)) }.orEmpty()

        query.multiselect(built.keys.map { it.expression })
        query.where(*(built.predicates + seek).toTypedArray())
        query.orderBy(built.keys.map { it.order(cb) })

        return rows(entityManager, query, limit)
    }

    /**
     * 식을 파생 테이블에서 한 번만 계산하고 바깥에서 비교한다. 첫 키가 비싼 식이거나(검색 점수) 조인한 표의 컬럼을 읽을 때(제목 앞부분) 쓴다.
     * 검색 점수를 WHERE에 쓰면 행마다 다시 계산해 10만 행에서 offset의 약 2배가 걸렸다(직접 측정, docs/PAGINATION.md §9.3). `offset 0`은
     * Postgres가 파생 테이블을 바깥 쿼리로 합치지 못하게 막아 식을 다시 계산하는 일을 없앤다. 대신 바깥 조건도 조인 아래로 못 내려가 후보 전체를 조인한다.
     */
    fun derivedRows(entityManager: EntityManager, after: List<Comparable<*>>?, limit: Int, spec: (AbstractQuery<*>) -> KeysetSpec): List<List<Comparable<*>>> {
        val cb = entityManager.criteriaBuilder as HibernateCriteriaBuilder
        val query = cb.createTupleQuery()
        val inner = query.subquery(Tuple::class.java) as JpaSubQuery<Tuple>
        val built = spec(inner)

        inner.multiselect(*built.keys.mapIndexed { i, key -> key.expression.alias("k$i") }.toTypedArray())
        inner.where(*built.predicates.toTypedArray())
        inner.offset(cb.literal(0))

        val derived = query.from(inner)
        val outerKeys = built.keys.mapIndexed { i, key -> SeekKey(derived.get<Any>("k$i"), key.descending) }

        query.multiselect(outerKeys.map { it.expression })
        after?.let { query.where(SeekPredicate.after(cb, outerKeys, it)) }
        query.orderBy(outerKeys.map { it.order(cb) })

        return rows(entityManager, query, limit)
    }

    private fun rows(entityManager: EntityManager, query: CriteriaQuery<Tuple>, limit: Int): List<List<Comparable<*>>> = entityManager
        .createQuery(query)
        .setMaxResults(limit)
        .resultList
        .map { row -> row.toArray().map { it as Comparable<*> } }

    /** size+1개 읽은 rows로 다음 쪽 유무를 정하고 이 쪽 엔티티만 불러온다. 두 조회 사이에 지워진 글은 빠진다. */
    fun <T : Any> slice(entityManager: EntityManager, entity: Class<T>, rows: List<List<Comparable<*>>>, size: Int, total: Long?, idOf: (T) -> Any?): KeysetSlice<T> {
        val page = rows.take(size)
        val ids = page.map { it.last() }
        val nextKeys = page.lastOrNull().takeIf { rows.size > size }

        if (ids.isEmpty()) {
            return KeysetSlice(emptyList(), nextKeys, total)
        }

        val cb = entityManager.criteriaBuilder
        val query = cb.createQuery(entity)
        val root = query.from(entity)
        query.select(root).where(root.get<Any>("id").`in`(ids))
        val loaded = entityManager.createQuery(query).resultList.associateBy(idOf)

        return KeysetSlice(ids.mapNotNull { loaded[it] }, nextKeys, total)
    }
}
