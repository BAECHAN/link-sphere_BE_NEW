package com.example.linksphere.global.job

/**
 * 요청 응답과 떼어 처리할 뒷일(AI 분석, 댓글 알림 등)을 맡긴다(docs/BE-ARCHITECTURE.md §7-5).
 *
 * 맡기는 쪽(domain)은 이 인터페이스만 안다. 어떻게 전달할지는 `app.job.publisher` 설정이 고른 구현이 정한다 —
 * 운영은 Lambda self-invoke(`infra.aws.LambdaJobPublisher`), 개발 환경은 같은 프로세스([InProcessJobPublisher]),
 * 로컬 1회성 도구는 생략([SkippingJobPublisher]).
 *
 * 트랜잭션이 커밋된 뒤에 부른다(AFTER_COMMIT 리스너 등). 커밋 전에 부르면 처리기가 아직 없는 행을 읽는다.
 */
interface JobPublisher {
    /**
     * [jobName] 처리기([JobHandler])에게 [event]를 넘긴다. [event]는 JSON으로 바뀌었다가 처리기에서 되돌려지므로
     * 그 도메인 `*DTO.kt`의 data class여야 한다. [logContext]는 로그에만 쓴다(예: `postId: …`).
     */
    fun publish(jobName: String, event: Any, logContext: String)
}

/** 뒷일 한 건의 모양 — `{"linksphereJob": 잡 이름, "event": 내용}`. LambdaHandler가 이 필드로 HTTP 요청과 구분한다. */
object JobMessage {
    const val NAME_FIELD = "linksphereJob"
    const val EVENT_FIELD = "event"

    // 필드 순서까지 예전 *JobPayload data class와 같게 둔다(LinkedHashMap)
    fun of(jobName: String, event: Any): Map<String, Any> = linkedMapOf(NAME_FIELD to jobName, EVENT_FIELD to event)
}
