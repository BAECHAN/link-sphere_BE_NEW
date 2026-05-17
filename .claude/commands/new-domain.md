Create a complete new domain called "$ARGUMENTS" in the Link-Sphere BE project.

## Project context

- Package root: `com.example.linksphere`
- Domain 패키지: `domain/<도메인명>/` — 평면 구조 (서브패키지 없음)
- 모든 파일은 하나의 디렉토리에 위치

Parse "$ARGUMENTS":

- Domain name (kebab-case): **$ARGUMENTS**
- Entity name (PascalCase): derive from domain name (e.g. "bookmark-folder" → "BookmarkFolder", "member-setting" → "MemberSetting")
- Table name (snake_case): derive from domain name (e.g. "bookmark-folder" → "bookmark_folders")

---

## Step 1: 기존 패턴 파악

Before creating any files, read these reference files to match the exact style:

- `src/main/kotlin/com/example/linksphere/domain/post/TablePost.kt` — Entity 패턴
- `src/main/kotlin/com/example/linksphere/domain/post/PostDTO.kt` — DTO 패턴
- `src/main/kotlin/com/example/linksphere/domain/post/PostRepository.kt` — Repository 패턴
- `src/main/kotlin/com/example/linksphere/domain/post/PostService.kt` — Service 패턴
- `src/main/kotlin/com/example/linksphere/domain/post/PostController.kt` — Controller 패턴
- `src/main/kotlin/com/example/linksphere/global/common/ApiResponse.kt` — 응답 포맷

---

## Step 2: 파일 생성 순서

### 1. `domain/<domain>/Table<Entity>.kt` — JPA 엔티티

```kotlin
package com.example.linksphere.domain.<domain>

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "<table_name>")
class Table<Entity>(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    // TODO: 도메인에 맞는 필드 추가

    @Column(name = "created_at", updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
```

### 2. `domain/<domain>/<Entity>Repository.kt` — Spring Data JPA

```kotlin
package com.example.linksphere.domain.<domain>

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface <Entity>Repository : JpaRepository<Table<Entity>, UUID> {
    // TODO: 도메인에 맞는 쿼리 메서드 추가
    fun findByUserId(userId: UUID): List<Table<Entity>>
    fun existsByIdAndUserId(id: UUID, userId: UUID): Boolean
}
```

### 3. `domain/<domain>/<Entity>DTO.kt` — Request/Response

```kotlin
package com.example.linksphere.domain.<domain>

import java.time.LocalDateTime
import java.util.UUID

data class Create<Entity>Request(
    // TODO: 생성에 필요한 필드
)

data class Update<Entity>Request(
    // TODO: 수정에 필요한 필드
)

data class <Entity>Response(
    val id: UUID,
    // TODO: 응답에 포함할 필드
    val createdAt: LocalDateTime
)
```

### 4. `domain/<domain>/<Entity>Service.kt` — 비즈니스 로직

```kotlin
package com.example.linksphere.domain.<domain>

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class <Entity>Service(
    private val <entity>Repository: <Entity>Repository
) {
    fun get<Entity>s(userId: UUID): List<<Entity>Response> {
        return <entity>Repository.findByUserId(userId).map { it.toResponse() }
    }

    @Transactional
    fun create<Entity>(userId: UUID, request: Create<Entity>Request): <Entity>Response {
        val entity = Table<Entity>(
            userId = userId,
            // TODO: request 필드 매핑
        )
        return <entity>Repository.save(entity).toResponse()
    }

    @Transactional
    fun update<Entity>(id: UUID, userId: UUID, request: Update<Entity>Request): <Entity>Response {
        val entity = <entity>Repository.findById(id).orElseThrow {
            <Entity>NotFoundException(id)
        }
        if (entity.userId != userId) throw ForbiddenException("Access denied")
        // TODO: 필드 업데이트
        return <entity>Repository.save(entity).toResponse()
    }

    @Transactional
    fun delete<Entity>(id: UUID, userId: UUID) {
        val entity = <entity>Repository.findById(id).orElseThrow {
            <Entity>NotFoundException(id)
        }
        if (entity.userId != userId) throw ForbiddenException("Access denied")
        <entity>Repository.delete(entity)
    }

    private fun Table<Entity>.toResponse() = <Entity>Response(
        id = id,
        // TODO: 필드 매핑
        createdAt = createdAt
    )
}
```

### 5. `domain/<domain>/<Entity>Controller.kt` — HTTP 엔드포인트

```kotlin
package com.example.linksphere.domain.<domain>

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/<domain-path>")
class <Entity>Controller(private val <entity>Service: <Entity>Service) {

    @GetMapping
    fun get<Entity>s(authentication: Authentication): ApiResponse<List<<Entity>Response>> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        return ApiResponse(HttpStatus.OK.value(), "<Entity> 목록 조회 성공", <entity>Service.get<Entity>s(userId))
    }

    @PostMapping
    fun create<Entity>(
        @RequestBody request: Create<Entity>Request,
        authentication: Authentication
    ): ApiResponse<<Entity>Response> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        return ApiResponse(HttpStatus.CREATED.value(), "<Entity> 생성 성공", <entity>Service.create<Entity>(userId, request))
    }

    @PatchMapping("/{id}")
    fun update<Entity>(
        @PathVariable id: UUID,
        @RequestBody request: Update<Entity>Request,
        authentication: Authentication
    ): ApiResponse<<Entity>Response> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        return ApiResponse(HttpStatus.OK.value(), "<Entity> 수정 성공", <entity>Service.update<Entity>(id, userId, request))
    }

    @DeleteMapping("/{id}")
    fun delete<Entity>(
        @PathVariable id: UUID,
        authentication: Authentication
    ): ApiResponse<Unit> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        <entity>Service.delete<Entity>(id, userId)
        return ApiResponse(HttpStatus.OK.value(), "<Entity> 삭제 성공", Unit)
    }
}
```

### 6. `global/exception/<Entity>NotFoundException.kt`

```kotlin
package com.example.linksphere.global.exception

import java.util.UUID

class <Entity>NotFoundException(id: UUID) :
    RuntimeException("<Entity> not found: $id")
```

### 7. `global/exception/GlobalExceptionHandler.kt` 에 핸들러 추가

```kotlin
@ExceptionHandler(<Entity>NotFoundException::class)
fun handle<Entity>NotFoundException(e: <Entity>NotFoundException): ResponseEntity<ErrorResponse> {
    val response = ErrorResponse(
        status = HttpStatus.NOT_FOUND.value(),
        code = "<ENTITY>_NOT_FOUND",
        message = e.message ?: "<Entity> not found"
    )
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response)
}
```

---

## Step 3: 완료 후 안내

생성 완료 후 사용자에게 알려줄 것:

1. `Table<Entity>.kt`의 TODO 필드를 실제 도메인 필드로 채워야 함
2. `<Entity>Service.kt`의 toResponse() 매핑을 완성해야 함
3. 인증 없이 접근 가능한 엔드포인트가 있으면 `SecurityConfig.kt`의 `permitAll()` 목록에 추가 필요
4. DB 테이블 생성 SQL이 필요하면 `src/main/resources/sql/` 에 추가
