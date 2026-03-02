package com.example.linksphere.infra.fcm

import com.example.linksphere.global.common.getUserId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/fcm")
class FcmTokenController(private val fcmTokenService: FcmTokenService) {

    // 기기 FCM 토큰 등록 (로그인 후 브라우저/앱에서 호출)
    @PostMapping("/token")
    fun registerToken(
        authentication: Authentication,
        @Valid @RequestBody request: RegisterFcmTokenRequest
    ): ResponseEntity<Void> {
        val userId = authentication.getUserId()
            ?: return ResponseEntity.status(401).build()
        fcmTokenService.registerToken(userId, request.token, request.platform)
        return ResponseEntity.ok().build()
    }

    // 기기 FCM 토큰 삭제 (로그아웃 시 호출)
    @DeleteMapping("/token")
    fun deleteToken(
        @Valid @RequestBody request: DeleteFcmTokenRequest
    ): ResponseEntity<Void> {
        fcmTokenService.deleteToken(request.token)
        return ResponseEntity.ok().build()
    }
}
