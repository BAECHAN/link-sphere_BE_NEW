package com.example.linksphere.domain.interaction

/**
 * 북마크 폴더 만들기·이름 바꾸기가 공유하는 입력 한도. BookmarkFolderService가 검사하고, CreateFolderRequest·
 * UpdateFolderRequest가 같은 값을 OpenAPI 스펙에 싣는다(FE가 스펙에서 상수를 생성해 쓴다 - docs/BE-ARCHITECTURE.md §7-12).
 */
object BookmarkFolderLimits {

    // 화면에서 잘리지 않게 정한 상한. 이름은 말줄임 없이 줄바꿈해 보여 주고,
    // 15자면 가장 좁은 자리(360px 폰의 폴더 카드)에서도 보통 2줄에 들어간다(FE docs/BOOKMARK.md "폴더 이름 길이", 2026-10-27).
    // 앞뒤 공백을 뗀 뒤 문자(코드포인트) 수로 센다 - 스펙의 maxLength는 이 셈법까지 담지 못해 값만 같다.
    // DB 컬럼(bookmark_folders.name varchar(100))은 그대로라 이미 저장된 16~100자 이름도 읽을 수 있다.
    const val MAX_NAME_LENGTH = 15
}
