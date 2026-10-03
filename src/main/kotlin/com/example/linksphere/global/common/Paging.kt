package com.example.linksphere.global.common

import org.springframework.data.domain.PageRequest

/**
 * 목록 API의 size를 상한으로 묶는다 - size는 클라이언트가 정하는 쿼리 파라미터라 상한이 없으면
 * `size=100000` 요청 한 번으로 DB 조회·응답 직렬화 부하가 그만큼 커진다(OWASP API4:2023
 * Unrestricted Resource Consumption, docs/TRAFFIC-MANAGEMENT.md 참고). FE는 모든 목록에서
 * 10을 쓴다(POST_PAGE_SIZE·COMMENT_PAGE_SIZE).
 */
object Paging {
    const val MAX_PAGE_SIZE = 50

    fun pageRequest(page: Int, size: Int): PageRequest = PageRequest.of(page, size.coerceIn(1, MAX_PAGE_SIZE))
}
