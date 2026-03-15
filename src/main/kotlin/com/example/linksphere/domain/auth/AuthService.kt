package com.example.linksphere.domain.auth

import com.example.linksphere.domain.auth.jwt.JwtTokenProvider
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.global.exception.InvalidCredentialsException
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

@Service
@Transactional(readOnly = true)
class AuthService(
        private val memberService: MemberService,
        private val jwtTokenProvider: JwtTokenProvider,
        private val passwordEncoder: org.springframework.security.crypto.password.PasswordEncoder,
        private val supabaseStorageService: SupabaseStorageService
) {

    @Transactional
    fun signup(request: SignupRequest): AccountResponse =
            toAccountResponse(
                    memberService.signup(
                            request.copy(password = passwordEncoder.encode(request.password))
                    )
            )

    fun login(request: LoginRequest): AuthResult {
        val member =
                try {
                    memberService.findByEmail(request.email)
                } catch (e: IllegalArgumentException) {
                    throw InvalidCredentialsException("Invalid email or password")
                }

        if (!passwordEncoder.matches(request.password, member.password)) {
            throw InvalidCredentialsException("Invalid email or password")
        }

        val accessToken = jwtTokenProvider.createAccessToken(member.id.toString())
        val refreshToken = jwtTokenProvider.createRefreshToken(member.id.toString())

        return AuthResult(accessToken, refreshToken)
    }

    fun refresh(refreshToken: String): AuthResult {
        try {
            jwtTokenProvider.validateToken(refreshToken)
        } catch (e: Exception) {
            throw com.example.linksphere.global.exception.InvalidTokenException(
                    "Invalid refresh token"
            )
        }

        val userId = jwtTokenProvider.getUserId(refreshToken)
        return AuthResult(jwtTokenProvider.createAccessToken(userId), refreshToken)
    }

    fun getAccount(userId: String): AccountResponse =
            toAccountResponse(memberService.findById(UUID.fromString(userId)))

    @Transactional
    fun updateAccount(userId: String, request: UpdateAccountRequest): AccountResponse =
            toAccountResponse(memberService.updateAccount(UUID.fromString(userId), request))

    fun uploadAvatar(file: MultipartFile): AvatarUploadResponse {
        val imageUrl = supabaseStorageService.uploadFile(file)
        return AvatarUploadResponse(imageUrl)
    }

    private fun toAccountResponse(member: TableMember): AccountResponse {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        return AccountResponse(
                id = member.id.toString(),
                email = member.email,
                nickname = member.nickname,
                image = member.image,
                createdAt = member.createdAt?.format(formatter) ?: "",
                updatedAt = member.updatedAt?.format(formatter) ?: ""
        )
    }
}
