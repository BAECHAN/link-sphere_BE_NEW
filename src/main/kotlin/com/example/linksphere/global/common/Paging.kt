package com.example.linksphere.global.common

import com.example.linksphere.global.exception.InvalidInputException

/**
 * 목록 API의 size를 상한으로 묶는다 - size는 클라이언트가 정하는 쿼리 파라미터라 상한이 없으면
 * `size=100000` 요청 한 번으로 DB 조회·응답 직렬화 부하가 그만큼 커진다(OWASP API4:2023
 * Unrestricted Resource Consumption, docs/TRAFFIC-MANAGEMENT.md 참고). FE는 모든 목록에서
 * 10을 쓴다(POST_PAGE_SIZE·COMMENT_PAGE_SIZE).
 */
object Paging {
    const val MAX_PAGE_SIZE = 50

    fun coerceSize(size: Int): Int = size.coerceIn(1, MAX_PAGE_SIZE)

    /**
     * 목록은 cursor로만 읽는다(docs/PAGINATION.md). page는 cursor 전환 전 번들만 보내므로 값이 무엇이든 400으로 끝낸다.
     * 무시하면 그 번들이 매번 첫 쪽을 받아 다음 쪽 요청을 멈추지 않는다 - 400이면 재시도 화면이 뜨고 새로고침으로 복구된다.
     */
    fun rejectPageParam(page: String?) {
        if (page != null) {
            throw InvalidInputException("page is no longer supported, use cursor")
        }
    }
}
