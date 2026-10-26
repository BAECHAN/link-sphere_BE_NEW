package com.example.linksphere.global.config

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
@EnableAsync
class AsyncConfig : AsyncConfigurer {

    private val logger = LoggerFactory.getLogger(AsyncConfig::class.java)

    override fun getAsyncExecutor(): Executor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 2
        executor.maxPoolSize = 5
        executor.queueCapacity = 10
        executor.setThreadNamePrefix("ai-async-")
        executor.setTaskDecorator(::propagateMdc)
        executor.initialize()
        return executor
    }

    // @Async 작업은 풀 스레드에서 돌아 호출한 스레드의 MDC(LambdaHandler가 넣는 awsRequestId)를 못 본다 - 제출 시점의
    // MDC를 복사해 작업 동안만 걸어 둔다. 풀 스레드는 다음 작업에 재사용되므로 끝나면 비운다.
    private fun propagateMdc(task: Runnable): Runnable {
        val context = MDC.getCopyOfContextMap()

        return Runnable {
            if (context != null) {
                MDC.setContextMap(context)
            }

            try {
                task.run()
            } finally {
                MDC.clear()
            }
        }
    }

    override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler = AsyncUncaughtExceptionHandler { ex, method, params ->
        logger.error("[AsyncError] Method: ${method.name}, Exception: ${ex.message}", ex)
    }
}
