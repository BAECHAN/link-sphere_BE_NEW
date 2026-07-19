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
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

// 폴더 목록 + 미분류(folder_id IS NULL) 북마크 수
data class FolderListResponse(
    val folders: List<FolderResponse>,
    val uncategorizedCount: Int,
)

// folderId = null → 미분류로 이동
data class MoveBookmarkRequest(val folderId: UUID?)

// 본인 폴더 ID 전체를 정렬된 순서대로 전송 — index가 sort_order가 됨
data class ReorderFoldersRequest(val folderIds: List<UUID>)

// 다중 선택 일괄 이동 — folderId = null 이면 미분류로
data class BatchMoveBookmarksRequest(val postIds: List<UUID>, val folderId: UUID?)

// 다중 선택 일괄 삭제
data class BatchDeleteBookmarksRequest(val postIds: List<UUID>)

// batch 결과 — 실제로 처리된 row 수 (본인 북마크가 아닌 ID는 무시되어 count에 안 잡힘)
data class BatchResultResponse(val processedCount: Int)
