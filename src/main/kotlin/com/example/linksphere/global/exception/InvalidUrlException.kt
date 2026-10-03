package com.example.linksphere.global.exception

/**
 * 사용자가 입력한 URL을 쓸 수 없을 때 - 원인별 code로 나눠, FE가 영어 message 문자열을 해석하지
 * 않고도 "도메인에 오타가 없는지" 같은 고칠 수 있는 안내를 고를 수 있게 한다(SafeUrlValidator,
 * FE docs/plans/2026-10-03-post-create-preview.md PR2-0).
 *
 * InvalidInputException을 상속하지 않고 따로 둔다 - SafeUrlValidator의 실패가 응답으로 나가는 곳은
 * 게시글 등록·수정·미리보기뿐이고(크롤러 안의 재검증은 UrlMetadataExtractor가 삼킨다), FE도
 * INVALID_INPUT 코드로 분기하는 곳이 없어 하위 타입으로 묶어 얻는 게 없다(2026-10-03 grep 확인).
 */
class InvalidUrlException(val code: String, message: String) : RuntimeException(message) {
    companion object {
        /** 빈 값·문법 오류·http(s)가 아닌 스킴·host 없음 */
        const val INVALID_URL = "INVALID_URL"

        /** DNS로 host를 찾을 수 없음 - 대개 도메인 오타 */
        const val URL_UNRESOLVABLE = "URL_UNRESOLVABLE"

        /** 사설·루프백·링크로컬 등 내부망 주소(SSRF 방지) */
        const val URL_NOT_ALLOWED = "URL_NOT_ALLOWED"
    }
}
