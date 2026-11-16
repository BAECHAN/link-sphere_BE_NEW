package com.example.linksphere.global.job

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component

/**
 * 잡 이벤트를 이름이 맞는 [JobHandler]에게 넘긴다. LambdaHandler(운영)와 [InProcessJobPublisher](개발 환경)가 함께 쓴다.
 *
 * 처리기는 생성자에서 바로 받지 않는다 — 처리기 → Service → [JobPublisher] → 이 클래스로 이어져 빈 순환이 된다.
 * 모든 빈이 만들어진 뒤([afterSingletonsInstantiated]) 한 번 모은다. Lambda에서는 이 시점이 스냅샷 전이다.
 */
@Component
class JobRunner(
    private val handlers: ObjectProvider<JobHandler>,
) : SmartInitializingSingleton {

    private lateinit var handlersByName: Map<String, JobHandler>

    override fun afterSingletonsInstantiated() {
        handlersByName =
            handlers.toList().groupBy { it.jobName }.mapValues { (jobName, sameName) ->
                sameName.singleOrNull() ?: throw IllegalStateException("잡 이름이 겹친다 - $jobName: ${sameName.map { it.javaClass.simpleName }}")
            }
    }

    /** [job]의 잡 이름에 맞는 처리기를 실행한다. 잡 이벤트가 아니거나 모르는 이름이면 아무것도 하지 않고 false. */
    fun run(job: JsonNode): Boolean {
        val handler = handlersByName[job.get(JobMessage.NAME_FIELD)?.asText()] ?: return false
        handler.handle(job)
        return true
    }
}
