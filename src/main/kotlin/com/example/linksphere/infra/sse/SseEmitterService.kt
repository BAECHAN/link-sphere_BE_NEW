package com.example.linksphere.infra.sse

import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Service
class SseEmitterService(private val sseConnectionLogRepository: SseConnectionLogRepository) {

    private val logger = LoggerFactory.getLogger(SseEmitterService::class.java)

    // userId -> list of emitters (한 유저가 여러 탭에서 접속 가능)
    private val emitters = ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>()

    // 5분 타임아웃
    fun subscribe(userId: UUID, timeout: Long = 5 * 60 * 1000L): SseEmitter {
        val emitter = SseEmitter(timeout)

        emitters.computeIfAbsent(userId) { CopyOnWriteArrayList() }.add(emitter)

        emitter.onCompletion {
            logger.info("[SSE] 연결 종료 (Completion) - userId: $userId")
            logConnectionEvent(userId, "COMPLETION", "Connection completed")
            removeEmitter(userId, emitter)
        }
        emitter.onTimeout {
            logger.info("[SSE] 연결 타임아웃 (Timeout) - userId: $userId")
            logConnectionEvent(userId, "TIMEOUT", "Connection timed out")
            emitter.complete()
            removeEmitter(userId, emitter)
        }
        emitter.onError {
            logger.error("[SSE] 연결 오류 (Error) - userId: $userId", it)
            logError(userId, it)
            emitter.completeWithError(it)
            removeEmitter(userId, emitter)
        }

        // 연결 확인용 초기 이벤트
        try {
            emitter.send(SseEmitter.event().name("connect").data("connected"))
        } catch (e: Exception) {
            logger.error("[SSE] 초기 이벤트 전송 실패", e)
            logError(userId, e)
            removeEmitter(userId, emitter)
        }

        logger.info("[SSE] 구독 등록 - userId: $userId, 현재 연결 수: ${emitters[userId]?.size}")

        // 하트비트 전송 (연결 유지)
        try {
            emitter.send(SseEmitter.event().name("ping").data("pong"))
        } catch (e: Exception) {
            logger.warn("[SSE] Ping 전송 실패", e)
            // Ping 실패는 로그를 남기지 않거나 필요시 남김
        }

        return emitter
    }

    fun sendToUser(userId: UUID, eventName: String, data: Any) {
        val userEmitters = emitters[userId]
        if (userEmitters.isNullOrEmpty()) {
            logger.warn("[SSE] 이벤트를 전송할 대상을 찾지 못함 (구독 안됨) - userId: $userId, event: $eventName")
            return
        }
        val deadEmitters = mutableListOf<SseEmitter>()

        userEmitters.forEach { emitter ->
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data))
            } catch (e: Exception) {
                logger.warn("[SSE] 이벤트 전송 실패 - userId: $userId", e)
                logError(userId, e)
                deadEmitters.add(emitter)
            }
        }

        userEmitters.removeAll(deadEmitters)
        if (userEmitters.isEmpty()) {
            emitters.remove(userId)
        }
    }

    private fun removeEmitter(userId: UUID, emitter: SseEmitter) {
        emitters[userId]?.remove(emitter)
        if (emitters[userId]?.isEmpty() == true) {
            emitters.remove(userId)
        }
    }

    @Scheduled(fixedRate = 45000) // 45초마다 하트비트 전송
    fun sendHeartbeat() {
        emitters.forEach { (userId, userEmitters) ->
            val deadEmitters = mutableListOf<SseEmitter>()
            userEmitters.forEach { emitter ->
                try {
                    // 주석(comment) 형태로 보냄으로써 클라이언트에서 이벤트 핸들러가 없어도 됨
                    // 혹은 "ping" 이벤트로 보내도 됨. 여기서는 안전하게 event ping 사용
                    emitter.send(SseEmitter.event().name("ping").data("keep-alive"))
                } catch (e: Exception) {
                    logError(userId, e)
                    deadEmitters.add(emitter)
                }
            }
            userEmitters.removeAll(deadEmitters)
            if (userEmitters.isEmpty()) {
                emitters.remove(userId)
            }
        }
    }

    fun getActiveUserIds(): List<UUID> {
        return emitters.keys.toList()
    }

    private fun logError(userId: UUID, e: Throwable) {
        val stackTrace = StringWriter().apply { e.printStackTrace(PrintWriter(this)) }.toString()
        val errorMessage =
                if (e is AsyncRequestNotUsableException) {
                    "Disconnected client (Broken pipe)"
                } else {
                    e.message
                }

        val log =
                TableSseConnectionLog(
                        userId = userId,
                        eventType = "ERROR",
                        errorMessage = errorMessage,
                        stackTrace = stackTrace,
                        occurredAt = LocalDateTime.now()
                )
        try {
            sseConnectionLogRepository.save(log)
        } catch (ex: Exception) {
            logger.error("Failed to save SSE connection log", ex)
        }
    }

    private fun logConnectionEvent(userId: UUID, eventType: String, message: String) {
        val log =
                TableSseConnectionLog(
                        userId = userId,
                        eventType = eventType,
                        errorMessage = message,
                        occurredAt = LocalDateTime.now()
                )
        try {
            sseConnectionLogRepository.save(log)
        } catch (ex: Exception) {
            logger.error("Failed to save SSE connection log", ex)
        }
    }
}
