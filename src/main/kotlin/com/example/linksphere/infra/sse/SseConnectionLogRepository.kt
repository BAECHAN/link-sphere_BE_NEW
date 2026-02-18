package com.example.linksphere.infra.sse

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface SseConnectionLogRepository : JpaRepository<TableSseConnectionLog, UUID>
