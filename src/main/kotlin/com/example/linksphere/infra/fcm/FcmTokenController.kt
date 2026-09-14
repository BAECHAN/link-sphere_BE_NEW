package com.example.linksphere.infra.fcm

import com.example.linksphere.global.common.getUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@Tag(name = "FCM 토큰", description = "기기 푸시 토큰 등록·해제")
@RestController
@RequestMapping("/fcm")
class FcmTokenController(private val fcmTokenService: FcmTokenService) {

    // 기기 FCM 토큰 등록 (로그인 후 브라우저/앱에서 호출)
    @Operation(
        summary = "FCM 토큰 등록",
        description = "다른 컨트롤러와 달리 ApiResponse 로 감싸지 않고 본문 없는 200/401 을 그대로 준다. " +
            "실패: 401(JWT subject 를 UUID 로 파싱하지 못했을 때)",
    )
    @PostMapping("/token")
    fun registerToken(
        authentication: Authentication,
        @Valid @RequestBody request: RegisterFcmTokenRequest,
    ): ResponseEntity<Void> {
        val userId = authentication.getUserId()
            ?: return ResponseEntity.status(401).build()
        fcmTokenService.registerToken(userId, request.token, request.platform)
        return ResponseEntity.ok().build()
    }

    // 기기 FCM 토큰 삭제 (로그아웃 시 호출)
    @Operation(
        summary = "FCM 토큰 해제",
        description = "본문 없는 200/401 을 그대로 준다. 실패: 401(JWT subject 를 UUID 로 파싱하지 못했을 때)",
    )
    @DeleteMapping("/token")
    fun deleteToken(
        authentication: Authentication,
        @Valid @RequestBody request: DeleteFcmTokenRequest,
    ): ResponseEntity<Void> {
        val userId = authentication.getUserId()
            ?: return ResponseEntity.status(401).build()
        fcmTokenService.deleteToken(userId, request.token)
        return ResponseEntity.ok().build()
    }
}
