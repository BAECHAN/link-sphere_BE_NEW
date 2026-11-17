package com.example.linksphere.domain.interaction

/**
 * 한 회원이 가질 수 있는 북마크 수의 한도. 북마크가 새로 생기는 모든 경로가 insert 전에 검사한다(BookmarkQuota.kt)
 * (docs/plans/2026-11-17-bookmark-limit-liked-filter.md).
 */
object BookmarkLimits {

    // 글 목록의 filter=isBookmarked와 북마크함의 제목순·열람순·검색은 그 회원의 북마크 글 id 전부를 post에 IN 목록으로 넘긴다.
    // 드라이버(pgjdbc)는 한 문장의 바인드 값이 65,535개를 넘으면 예외를 던지므로 그 전에 저장을 막는다. 10,000은
    // PostService.POST_ID_FILTER_WARN_SIZE·PostCandidateService.CANDIDATE_WARN_SIZE와 같은 값이다 - 한도가 지켜지면 북마크 때문에는 그 경고가 울리지 않는다
    // (PostService의 경고는 좋아요 글 id 목록에도 걸린다).
    // 세고 나서 넣기 때문에 동시 요청으로 몇 개 넘을 수 있다(잠그지 않는다 - 벽까지 여유가 크다).
    const val MAX_BOOKMARKS_PER_MEMBER = 10_000
}
