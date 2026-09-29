package com.example.linksphere.infra.fcm

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "fcm_tokens")
class TableFcmToken(
    @Id
    @Column(name = "id", nullable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "token", nullable = false, unique = true, columnDefinition = "text")
    val token: String,

    // WEB, ANDROID, IOS
    @Column(name = "platform", nullable = false, length = 10)
    val platform: String = "WEB",

    // 이 토큰을 등록한 세션의 회전 계열(TableMemberSession.familyId). 그 계열이 죽으면
    // (로그아웃·재사용탐지·비밀번호변경) 이 토큰도 발송 대상에서 제외된다(FcmService.sendToUser).
    // nullable: 이 컬럼 도입 이전에 등록된 레거시 행은 계열을 모르므로 즉시 비활성 취급된다.
    @Column(name = "session_family_id")
    val sessionFamilyId: UUID? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
