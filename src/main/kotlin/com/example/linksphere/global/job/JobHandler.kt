package com.example.linksphere.global.job

import com.fasterxml.jackson.databind.JsonNode

/**
 * 뒷일 한 종류를 처리한다. 각 도메인이 `@Component`로 구현하고 [JobRunner]가 [jobName]으로 찾아 부른다.
 *
 * [jobName]은 운영에서 떠 있는 호출과 EventBridge 룰이 쓰는 값이라 바꾸지 않는다(목록은 docs/BE-ARCHITECTURE.md §1).
 */
interface JobHandler {
    val jobName: String

    /** [job]은 잡 이벤트 전체다(`{"linksphereJob": …, "event": …}`). 내용은 [JobMessage.EVENT_FIELD]에서 꺼낸다. */
    fun handle(job: JsonNode)
}
