package com.example.linksphere.infra.sse

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "sse_connection_logs")
class TableSseConnectionLog(
        @Id
        @GeneratedValue(strategy = GenerationType.AUTO)
        @Column(name = "id", nullable = false)
        val id: UUID? = null,
        @Column(name = "user_id", nullable = false) val userId: UUID,
        @Column(name = "event_type", nullable = false)
        val eventType: String, // ERROR, TIMEOUT, COMPLETION
        @Column(name = "error_message", columnDefinition = "text") val errorMessage: String? = null,
        @Column(name = "stack_trace", columnDefinition = "text") val stackTrace: String? = null,
        @Column(name = "occurred_at", nullable = false)
        val occurredAt: LocalDateTime = LocalDateTime.now()
)
