package com.example.linksphere.infra.sse

import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/sse/debug")
class SseDebugController(private val sseEmitterService: SseEmitterService) {

    @GetMapping("/users")
    fun getActiveUsers(): List<UUID> {
        return sseEmitterService.getActiveUserIds()
    }
}
