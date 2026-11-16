package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryInfo
import com.example.linksphere.domain.category.CategoryResponse
import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.interaction.BookmarkFolderItemRepository
import com.example.linksphere.domain.interaction.BookmarkFolderRepository
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.interaction.PostInteractionAdapter
import com.example.linksphere.domain.interaction.PostReactionRepository
import com.example.linksphere.domain.interaction.TableBookmarkFolderItem
import com.example.linksphere.domain.member.MemberProfile
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.member.TableMember
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant
import java.util.UUID

/**
 * PostService.kt에서 추출된 PostResponseAssembler의 응답 조립 로직을 검증한다.
 * (전체 추출 배경: PostResponseAssembler.kt 상단 KDoc 참고)
 */
@ExtendWith(MockitoExtension::class)
class PostResponseAssemblerTest {

    @Mock private lateinit var memberService: MemberService

    @Mock private lateinit var bookmarkRepository: BookmarkRepository

    @Mock private lateinit var bookmarkFolderItemRepository: BookmarkFolderItemRepository

    @Mock private lateinit var postReactionRepository: PostReactionRepository

    @Mock private lateinit var postCommentPort: PostCommentPort

    @Mock private lateinit var categoryService: CategoryService

    @Mock private lateinit var bookmarkFolderRepository: BookmarkFolderRepository

    private lateinit var assembler: PostResponseAssembler

    // 좋아요·북마크는 interaction의 PostInteractionAdapter가 읽는다. 실제 구현에 mock 저장소를 물려 응답에 실리는 값과
    // 쿼리 횟수를 예전과 같은 단언으로 본다
    @BeforeEach
    fun setUp() {
        assembler =
            PostResponseAssembler(
                memberService = memberService,
                postInteractionPort = PostInteractionAdapter(bookmarkRepository, bookmarkFolderItemRepository, bookmarkFolderRepository, postReactionRepository),
                postCommentPort = postCommentPort,
                categoryService = categoryService,
            )
    }

    // 글은 카테고리를 id로만 든다 - 이름은 CategoryService에서 받아 id 순으로 붙이고, 없는 id는 응답에서 뺀다
    @Test
    fun `convertToResponse 는 카테고리 id를 이름으로 바꿔 id 순으로 싣는다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post =
            TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", categoryIds = mutableSetOf(2L, 1L, 999L))
        val owner = TableMember(id = ownerId, email = "owner@example.com", password = "enc", nickname = "owner")

        `when`(memberService.getProfile(ownerId)).thenReturn(MemberProfile.from(owner))
        `when`(categoryService.getCategoryInfosByIds(setOf(2L, 1L, 999L)))
            .thenReturn(listOf(CategoryInfo(2L, "AI", "ai"), CategoryInfo(1L, "프론트엔드", "frontend")))

        val result = assembler.convertToResponse(post, null)

        assertEquals(listOf(CategoryResponse(1L, "프론트엔드"), CategoryResponse(2L, "AI")), result.categories)
    }

    @Test
    fun `convertToResponse 의 stats bookmarkCount 는 소속 폴더 수가 아니라 북마크 row 수다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)
        val owner = TableMember(id = ownerId, email = "owner@example.com", password = "enc", nickname = "owner")

        `when`(memberService.getProfile(ownerId)).thenReturn(MemberProfile.from(owner))
        // countByPostId(=2) 와 실제 소속 폴더 개수(=3)를 의도적으로 다르게 둔다 —
        // stats.bookmarkCount 가 소속 수가 아니라 북마크 row 수를 세는지 확인하기 위함.
        `when`(bookmarkRepository.countByPostId(postId)).thenReturn(2L)
        `when`(bookmarkRepository.existsByUserIdAndPostId(ownerId, postId)).thenReturn(true)
        `when`(bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(ownerId, postId))
            .thenReturn(listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
        lenient().`when`(postReactionRepository.countByPostId(postId)).thenReturn(0L)
        lenient().`when`(postReactionRepository.existsByUserIdAndPostId(ownerId, postId)).thenReturn(false)
        lenient().`when`(postCommentPort.countByPostId(postId)).thenReturn(0)

        val result = assembler.convertToResponse(post, ownerId)

        assertEquals(2, result.stats.bookmarkCount)
        assertEquals(3, result.userInteractions.bookmarkFolderIds.size)
    }

    @Test
    fun `convertToResponse 는 탈퇴 유예 중인 작성자의 닉네임·이미지를 null로 감춘다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)
        val pendingOwner =
            TableMember(
                id = ownerId,
                email = "owner@example.com",
                password = "enc",
                nickname = "owner",
                image = "https://example.com/a.png",
                deletionRequestedAt = Instant.now(),
            )

        `when`(memberService.getProfile(ownerId)).thenReturn(MemberProfile.from(pendingOwner))
        lenient().`when`(bookmarkRepository.countByPostId(postId)).thenReturn(0L)
        lenient().`when`(bookmarkRepository.existsByUserIdAndPostId(ownerId, postId)).thenReturn(false)
        lenient().`when`(postReactionRepository.countByPostId(postId)).thenReturn(0L)
        lenient().`when`(postReactionRepository.existsByUserIdAndPostId(ownerId, postId)).thenReturn(false)
        lenient().`when`(postCommentPort.countByPostId(postId)).thenReturn(0)

        val result = assembler.convertToResponse(post, ownerId)

        assertEquals(null, result.author.nickname)
        assertEquals(null, result.author.image)
    }

    @Test
    fun `convertToResponse 는 비로그인 사용자면 isBookmarked 와 isLiked 를 false 로 채운다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)
        val owner = TableMember(id = ownerId, email = "owner@example.com", password = "enc", nickname = "owner")

        `when`(memberService.getProfile(ownerId)).thenReturn(MemberProfile.from(owner))
        `when`(bookmarkRepository.countByPostId(postId)).thenReturn(0L)
        `when`(postReactionRepository.countByPostId(postId)).thenReturn(0L)
        `when`(postCommentPort.countByPostId(postId)).thenReturn(0)

        val result = assembler.convertToResponse(post, null)

        assertEquals(false, result.userInteractions.isBookmarked)
        assertEquals(false, result.userInteractions.isLiked)
        assertEquals(emptyList<UUID>(), result.userInteractions.bookmarkFolderIds)
    }

    @Test
    fun `buildResponsesFromPosts 는 빈 목록이면 조회 없이 빈 목록을 반환한다`() {
        val result = assembler.buildResponsesFromPosts(emptyList(), UUID.randomUUID())

        assertEquals(emptyList<PostResponse>(), result)
    }

    @Test
    fun `buildResponsesFromPosts 는 소속 폴더를 한 번의 쿼리로 채운다`() {
        val userId = UUID.randomUUID()
        val postId1 = UUID.randomUUID()
        val postId2 = UUID.randomUUID()
        val folderId1 = UUID.randomUUID()
        val folderId2 = UUID.randomUUID()
        val post1 =
            TablePost(id = postId1, userId = userId, url = "https://example.com/1", title = "글1", isPrivate = false)
        val post2 =
            TablePost(id = postId2, userId = userId, url = "https://example.com/2", title = "글2", isPrivate = false)
        val member = TableMember(id = userId, email = "user@example.com", password = "enc", nickname = "user")

        `when`(memberService.findProfiles(listOf(userId))).thenReturn(mapOf(userId to MemberProfile.from(member)))
        `when`(bookmarkRepository.countByPostIdIn(listOf(postId1, postId2))).thenReturn(emptyList())
        `when`(bookmarkRepository.findAllByUserIdAndPostIdIn(userId, listOf(postId1, postId2)))
            .thenReturn(emptyList())
        `when`(bookmarkFolderItemRepository.findAllByUserIdAndPostIdIn(userId, listOf(postId1, postId2)))
            .thenReturn(
                listOf(
                    TableBookmarkFolderItem(userId, postId1, folderId1),
                    TableBookmarkFolderItem(userId, postId1, folderId2),
                ),
            )
        `when`(postReactionRepository.countByPostIdIn(listOf(postId1, postId2)))
            .thenReturn(emptyList())
        `when`(postReactionRepository.findAllByUserIdAndPostIdIn(userId, listOf(postId1, postId2)))
            .thenReturn(emptyList())
        `when`(postCommentPort.countByPostIds(listOf(postId1, postId2))).thenReturn(emptyMap())

        val result = assembler.buildResponsesFromPosts(listOf(post1, post2), userId)

        val byId = result.associateBy { it.id }
        assertEquals(listOf(folderId1, folderId2), byId.getValue(postId1).userInteractions.bookmarkFolderIds)
        assertEquals(emptyList<UUID>(), byId.getValue(postId2).userInteractions.bookmarkFolderIds)
        verify(bookmarkFolderItemRepository, times(1))
            .findAllByUserIdAndPostIdIn(userId, listOf(postId1, postId2))
    }

    @Test
    fun `buildResponsesFromPosts 는 비로그인 사용자면 폴더·북마크 조회를 건너뛴다`() {
        val postId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)
        val member = TableMember(id = ownerId, email = "owner@example.com", password = "enc", nickname = "owner")

        `when`(memberService.findProfiles(listOf(ownerId))).thenReturn(mapOf(ownerId to MemberProfile.from(member)))
        `when`(bookmarkRepository.countByPostIdIn(listOf(postId))).thenReturn(emptyList())
        `when`(postReactionRepository.countByPostIdIn(listOf(postId))).thenReturn(emptyList())
        `when`(postCommentPort.countByPostIds(listOf(postId))).thenReturn(emptyMap())

        val result = assembler.buildResponsesFromPosts(listOf(post), null)

        assertEquals(false, result.single().userInteractions.isBookmarked)
        verify(bookmarkFolderItemRepository, org.mockito.Mockito.never()).findAllByUserIdAndPostIdIn(any(), any())
        verify(bookmarkRepository, org.mockito.Mockito.never()).findAllByUserIdAndPostIdIn(any(), any())
    }

    private fun <T> any(): T {
        org.mockito.ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }
}
