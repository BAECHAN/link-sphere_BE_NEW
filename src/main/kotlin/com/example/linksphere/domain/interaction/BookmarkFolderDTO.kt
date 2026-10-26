package com.example.linksphere.domain.interaction

import java.time.LocalDateTime
import java.util.UUID

data class CreateFolderRequest(val name: String)

data class UpdateFolderRequest(val name: String)

data class FolderResponse(
    val id: UUID,
    val name: String,
    val sortOrder: Int,
    val bookmarkCount: Int,
    val lastUsedAt: LocalDateTime? = null, // 이 폴더에 마지막으로 저장한 시각 — 한 번도 저장 안 됐으면 null
    val pinnedAt: LocalDateTime? = null, // 고정한 시각 — 고정하지 않았으면 null
)

// 고정·해제 응답 — 목록 캐시의 그 폴더 pinnedAt만 고친다. FolderResponse를 돌려주면 lastUsedAt이 비어 FE 캐시를 덮을 때
// 최근 저장 순서가 깨진다
data class FolderPinResponse(
    val id: UUID,
    val pinnedAt: LocalDateTime?,
)

// 폴더 목록 + 미분류(소속 0개) 북마크 수. 전체 배지는 없음 — 중복 없는 총합을 보여주려면
// 서버 필드가 필요한데, 이번엔 `전체` 행에 숫자 자체를 표시하지 않기로 했다.
data class FolderListResponse(
    val folders: List<FolderResponse>,
    val uncategorizedCount: Int,
)

// 소속 변경 API(추가/제거/전체해제) 공통 응답 — 변경 후 권위 상태를 돌려줘 FE 가 재조회 없이 정합을 맞춘다
data class BookmarkFoldersResponse(
    val postId: UUID,
    val isBookmarked: Boolean,
    val folderIds: List<UUID>,
)

// 일괄 추가/제거 — folderId 는 path 로 받는다
data class BatchFolderBookmarksRequest(val postIds: List<UUID>)

// 다중 선택 일괄 삭제
data class BatchDeleteBookmarksRequest(val postIds: List<UUID>)

// batch 결과 개수. 엔드포인트마다 세는 기준이 다르다(InteractionController의 Swagger 설명과 같음):
// 폴더에 일괄 추가는 요청한 postId 개수 그대로(이미 있던 것도 센다), 폴더에서 일괄 제거·북마크 일괄 삭제는 실제로 지운 행 수
// (본인 북마크가 아닌 ID는 무시돼 안 잡힌다).
data class BatchResultResponse(val processedCount: Int)
