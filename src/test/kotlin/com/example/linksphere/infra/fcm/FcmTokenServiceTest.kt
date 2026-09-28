package com.example.linksphere.infra.fcm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class FcmTokenServiceTest {

    @Mock private lateinit var fcmTokenRepository: FcmTokenRepository

    @InjectMocks private lateinit var fcmTokenService: FcmTokenService

    // Kotlin에서 ArgumentCaptor.forClass(...)의 반환 타입은 Java platform type이라
    // 캐스팅 없이 쓰면 captureValue의 반환값에 Kotlin이 checkNotNull을 끼워 넣어 NPE가
    // 난다(OrphanImageCleanupRunnerTest·PostAiBackfillRunnerTest와 동일 이유 - 명시적
    // 캐스팅 필요). id/userId/token/platform은 non-null 파라미터라 이 헬퍼+캐스팅을
    // 거치고, sessionFamilyId는 nullable(UUID?)이라 captor.capture()를 그대로 써도 된다.
    private fun <T> captureValue(captor: ArgumentCaptor<T>): T {
        captor.capture()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun uuidCaptor(): ArgumentCaptor<UUID> = ArgumentCaptor.forClass(UUID::class.java) as ArgumentCaptor<UUID>

    @Suppress("UNCHECKED_CAST")
    private fun stringCaptor(): ArgumentCaptor<String> = ArgumentCaptor.forClass(String::class.java) as ArgumentCaptor<String>

    @Test
    fun `registerToken은 upsert 쿼리에 userId, token, platform, sessionFamilyId를 그대로 넘긴다`() {
        val userId = UUID.randomUUID()
        val familyId = UUID.randomUUID()

        fcmTokenService.registerToken(userId, "device-token", "WEB", familyId)

        val idCaptor = uuidCaptor()
        val userIdCaptor = uuidCaptor()
        val tokenCaptor = stringCaptor()
        val platformCaptor = stringCaptor()
        val familyIdCaptor = ArgumentCaptor.forClass(UUID::class.java)
        verify(fcmTokenRepository).upsertToken(
            captureValue(idCaptor),
            captureValue(userIdCaptor),
            captureValue(tokenCaptor),
            captureValue(platformCaptor),
            familyIdCaptor.capture(),
        )

        assertEquals(userId, userIdCaptor.value)
        assertEquals("device-token", tokenCaptor.value)
        assertEquals("WEB", platformCaptor.value)
        assertEquals(familyId, familyIdCaptor.value)
    }

    @Test
    fun `registerToken은 세션 정보를 못 읽었으면(familyId 없음) null로 upsert한다`() {
        val userId = UUID.randomUUID()

        fcmTokenService.registerToken(userId, "device-token", "WEB", null)

        val familyIdCaptor = ArgumentCaptor.forClass(UUID::class.java)
        verify(fcmTokenRepository).upsertToken(
            captureValue(uuidCaptor()),
            captureValue(uuidCaptor()),
            captureValue(stringCaptor()),
            captureValue(stringCaptor()),
            familyIdCaptor.capture(),
        )

        assertNull(familyIdCaptor.value)
    }

    @Test
    fun `deleteToken은 해당 유저·토큰 조합만 지운다`() {
        val userId = UUID.randomUUID()

        fcmTokenService.deleteToken(userId, "device-token")

        verify(fcmTokenRepository).deleteByUserIdAndToken(userId, "device-token")
    }

    @Test
    fun `getTokensByUserId는 저장된 행에서 토큰 문자열만 뽑아 반환한다`() {
        val userId = UUID.randomUUID()
        val tokens = listOf(
            TableFcmToken(userId = userId, token = "token-1"),
            TableFcmToken(userId = userId, token = "token-2"),
        )
        `when`(fcmTokenRepository.findAllByUserId(userId)).thenReturn(tokens)

        val result = fcmTokenService.getTokensByUserId(userId)

        assertEquals(listOf("token-1", "token-2"), result)
    }
}
