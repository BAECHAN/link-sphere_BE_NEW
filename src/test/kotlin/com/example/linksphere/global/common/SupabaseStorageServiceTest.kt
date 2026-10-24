package com.example.linksphere.global.common

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Supabase가 연결은 받았는데 응답하지 않을 때 호출이 끝없이 멈추지 않는지 확인한다. 로컬 HttpServer가 요청을
 * 받고 응답하지 않게 해 진짜 소켓의 읽기 타임아웃을 관찰한다(SafeConnectTest와 같은 방식). 기본 생성자
 * RestTemplate()에는 타임아웃이 없어 업로드 서명 요청이 Lambda 제한시간까지 붙잡혔다.
 */
class SupabaseStorageServiceTest {

    private lateinit var server: HttpServer
    private val release = CountDownLatch(1)

    @AfterEach
    fun tearDown() {
        release.countDown()
        server.stop(0)
    }

    @Test
    fun `스토리지가 응답하지 않으면 업로드 서명 발급이 읽기 타임아웃 뒤 실패한다`() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            // 응답을 보내지 않고 붙잡아 둔다 - 테스트가 끝나면 tearDown이 풀어 준다
            release.await(30, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        val service = SupabaseStorageService("http://127.0.0.1:${server.address.port}", "test-key", "test-bucket")

        // 읽기 타임아웃보다 넉넉하되 "끝없이 멈춤"은 잡을 만큼만 기다린다
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            assertThrows(RuntimeException::class.java) { service.createSignedUploadUrl("png") }
        }
    }
}
