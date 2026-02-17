package com.example.linksphere.global.common

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ApiResponse<T>(
        val status: Int,
        val message: String,
        val data: T,
        val timestamp: String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
)
