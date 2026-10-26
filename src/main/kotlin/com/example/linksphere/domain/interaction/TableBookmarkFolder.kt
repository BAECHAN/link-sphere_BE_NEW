package com.example.linksphere.domain.interaction

import jakarta.persistence.*
import org.hibernate.annotations.DynamicUpdate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(
    name = "bookmark_folders",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_bookmark_folders_user_name",
            columnNames = ["user_id", "name"],
        ),
        // bookmark_folder_items 의 복합 FK (user_id, folder_id) 대상 — 소속의 소유자 == 폴더의 소유자를 DB가 강제
        UniqueConstraint(
            name = "uk_bookmark_folders_user_id",
            columnNames = ["user_id", "id"],
        ),
    ],
    indexes = [
        Index(
            name = "idx_bookmark_folders_user_sort",
            columnList = "user_id, sort_order",
        ),
    ],
)
// 이름 변경과 고정은 같은 행의 다른 컬럼을 고친다. 기본 UPDATE는 모든 컬럼을 써서 겹친 요청끼리 서로의 값을 덮으므로 바뀐 컬럼만
// 쓴다(BookmarkFolderRepositoryIntegrationTest에서 재현)
@DynamicUpdate
class TableBookmarkFolder(
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "created_at", updatable = false, nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    // 고정한 시각. null이면 고정하지 않은 폴더다 - 고정 구획은 이 시각 오름차순이라 새로 고정한 폴더가 아래에 붙는다
    @Column(name = "pinned_at")
    var pinnedAt: LocalDateTime? = null,
)
