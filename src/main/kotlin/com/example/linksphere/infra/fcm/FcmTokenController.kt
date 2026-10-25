package com.example.linksphere.infra.fcm

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getSessionFamilyId
import com.example.linksphere.global.common.getUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@Tag(name = "FCM 토큰", description = "기기 푸시 토큰 등록·해제")
@RestController
@RequestMapping("/fcm")
class FcmTokenController(private val fcmTokenService: FcmTokenService) {

    // 기기 FCM 토큰 등록 (로그인 후 브라우저/앱에서 호출)
    @Operation(
        summary = "FCM 토큰 등록",
        description = "로그인 후 브라우저·앱에서 호출한다. data 는 비어 있다.",
    )
    @PostMapping("/token")
    fun registerToken(
        authentication: Authentication,
        @Valid @RequestBody request: RegisterFcmTokenRequest,
    ): ApiResponse<Unit> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        fcmTokenService.registerToken(userId, request.token, request.platform, authentication.getSessionFamilyId())
        return ApiResponse(HttpStatus.OK.value(), "FCM 토큰 등록 성공", Unit)
    }

    // 기기 FCM 토큰 삭제 (로그아웃 시 호출)
    @Operation(
        summary = "FCM 토큰 해제",
        description = "data 는 비어 있다.",
    )
    @DeleteMapping("/token")
    fun deleteToken(
        authentication: Authentication,
        @Valid @RequestBody request: DeleteFcmTokenRequest,
    ): ApiResponse<Unit> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        fcmTokenService.deleteToken(userId, request.token)
        return ApiResponse(HttpStatus.OK.value(), "FCM 토큰 해제 성공", Unit)
    }
}
