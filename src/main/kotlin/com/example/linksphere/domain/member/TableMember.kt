package com.example.linksphere.domain.member

import jakarta.persistence.*
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "members")
@DynamicUpdate
class TableMember(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", nullable = false)
    val id: UUID? = null,
    // 회원탈퇴 시 익명화 대상이라 var로 둔다(AccountDeletionService).
    @Column(name = "email", nullable = false, unique = true) var email: String,
    // 비밀번호 변경·회원탈퇴(익명화) 시 갱신되므로 var로 둔다.
    @Column(name = "password", nullable = false) // Encrypted password
    var password: String,
    @Column(name = "nickname") var nickname: String? = null,
    @Column(name = "image") var image: String? = null,
    @Column(name = "created_at") val createdAt: LocalDateTime? = LocalDateTime.now(),
    @Column(name = "updated_at") var updatedAt: LocalDateTime? = LocalDateTime.now(),
    // RSS 피드 자동 수집 봇 계정 여부. sql/create_feed_sources.sql이 컬럼 추가 + 봇 계정 INSERT를
    // 함께 실행하므로, 이 필드가 매핑된 코드가 배포되기 전에 반드시 그 SQL부터 실행해야 한다
    // (컬럼 없이 배포하면 모든 member SELECT가 실패한다).
    @Column(name = "is_bot", nullable = false) val isBot: Boolean = false,
    // 이메일 인증 여부. 글쓰기·댓글쓰기 게이트에만 쓴다(로그인은 막지 않음).
    // sql/add_member_auth_columns.sql이 기존 가입자를 전부 true로 그랜드파더링한다.
    @Column(name = "email_verified", nullable = false) var emailVerified: Boolean = false,
    // 회원탈퇴 시각. 채워지면 members 행 자체는 익명화된 채로 남고(F4 - 글·댓글이 이 행을
    // 계속 참조), 로그인은 더 이상 불가능해진다(password가 매칭 불가능한 값으로 바뀜).
    // sql/add_member_auth_columns.sql이 컬럼을 추가한다.
    @Column(name = "deleted_at") var deletedAt: Instant? = null,
)
