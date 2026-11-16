package com.example.linksphere.infra.aws

import com.example.linksphere.global.job.JobMessage
import com.example.linksphere.global.job.JobPublisher
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

// 운영용 — 뒷일을 이 Lambda 함수 자신에게 비동기(Event) 호출로 넘긴다. 받는 쪽은 LambdaHandler가 JobRunner로 넘긴다.
// 같은 실행 환경 안에서 스레드만 백그라운드로 돌리면 handleRequest() 반환 후 컨테이너가 얼어붙어 중단될 수 있다
// (과거 @Async 제거 사유). LambdaHandler가 init에서 app.job.publisher=lambda를 넣어 이 구현을 고른다.
@Component
@ConditionalOnProperty(name = ["app.job.publisher"], havingValue = "lambda")
class LambdaJobPublisher(
    private val lambdaSelfInvoker: LambdaSelfInvoker,
) : JobPublisher {

    private val logger = LoggerFactory.getLogger(LambdaJobPublisher::class.java)

    override fun publish(jobName: String, event: Any, logContext: String) {
        val dispatched = lambdaSelfInvoker.invoke(JobMessage.of(jobName, event), logContext)

        if (!dispatched) {
            logger.warn("[LambdaJobPublisher] 작업 발행 생략(함수 이름 없음) - $jobName, $logContext")
        }
    }
}
