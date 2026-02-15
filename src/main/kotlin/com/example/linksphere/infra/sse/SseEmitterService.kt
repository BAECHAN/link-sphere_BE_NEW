package com.example.linksphere.infra.sse

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Service
class SseEmitterService {

    private val logger = LoggerFactory.getLogger(SseEmitterService::class.java)

    // userId -> list of emitters (한 유저가 여러 탭에서 접속 가능)
    private val emitters = ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>()

    // 5분 타임아웃
    fun subscribe(userId: UUID, timeout: Long = 5 * 60 * 1000L): SseEmitter {
        val emitter = SseEmitter(timeout)

        emitters.computeIfAbsent(userId) { CopyOnWriteArrayList() }.add(emitter)

        emitter.onCompletion { removeEmitter(userId, emitter) }
        emitter.onTimeout {
            emitter.complete()
            removeEmitter(userId, emitter)
        }
        emitter.onError {
            emitter.completeWithError(it)
            removeEmitter(userId, emitter)
        }

        // 연결 확인용 초기 이벤트
        try {
            emitter.send(SseEmitter.event().name("connect").data("connected"))
        } catch (e: Exception) {
            logger.error("[SSE] 초기 이벤트 전송 실패", e)
            removeEmitter(userId, emitter)
        }

        logger.info("[SSE] 구독 등록 - userId: $userId, 현재 연결 수: ${emitters[userId]?.size}")

        // 하트비트 전송 (연결 유지)
        try {
            emitter.send(SseEmitter.event().name("ping").data("pong"))
        } catch (e: Exception) {
            logger.warn("[SSE] Ping 전송 실패", e)
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
}
