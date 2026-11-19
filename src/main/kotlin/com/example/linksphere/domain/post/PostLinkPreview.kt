package com.example.linksphere.domain.post

/**
 * 댓글에 붙은 이 서비스의 글 주소를 링크 카드로 보여 줄 때 쓰는 글 정보다. 다른 패키지(comment)가 TablePost를 직접 보지 않게 하는
 * 전용 객체다(member의 MemberProfile과 같은 자리). 볼 수 있는 글만 이 모양으로 나간다(PostLookupService.findVisiblePreviews).
 */
data class PostLinkPreview(val title: String, val description: String?, val ogImage: String?)
