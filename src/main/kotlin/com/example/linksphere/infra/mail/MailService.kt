package com.example.linksphere.infra.mail

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model.Body
import software.amazon.awssdk.services.ses.model.Content
import software.amazon.awssdk.services.ses.model.Destination
import software.amazon.awssdk.services.ses.model.Message
import software.amazon.awssdk.services.ses.model.SendEmailRequest

/**
 * AWS SES로 메일을 보낸다. 실패해도 예외를 절대 밖으로 던지지 않고 로그만 남긴다 -
 * 비밀번호 찾기·이메일 인증 발송이 실패했다고 그 API 자체(가입, 재발송 요청)가 실패하면
 * 안 된다(docs/plans/2026-09-28-auth-hardening.md Phase 6). 발신 주소(app.mail.from)가
 * 비어있으면(SES 발신 식별자 미검증 - 도메인 미확정 상태) 발송 자체를 건너뛴다 -
 * Phase 0의 ORIGIN_VERIFY_SECRET 미설정 시 fail-open과 같은 원칙.
 */
@Service
class MailService(
    @Value("\${app.mail.from:}") private val fromAddress: String,
) {
    private val logger = LoggerFactory.getLogger(MailService::class.java)

    // LambdaSelfInvoker와 같은 이유로 lazy + UrlConnectionHttpClient를 명시한다 - SnapStart
    // 체크포인트 이전(Spring 컨텍스트 초기화 시점)에 클라이언트를 만들면 내부적으로 연 소켓이
    // 체크포인트를 방해할 수 있어, 실제 발송이 일어나는 첫 호출 시점까지 생성을 미룬다.
    private val sesClient: SesClient by lazy {
        SesClient.builder()
            .region(Region.of(System.getenv("AWS_REGION") ?: "ap-northeast-1"))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build()
    }

    fun send(to: String, subject: String, htmlBody: String) {
        if (fromAddress.isBlank()) {
            logger.warn("[MailService] app.mail.from 미설정 - 발송 생략(to=$to)")
            return
        }

        try {
            val request =
                SendEmailRequest.builder()
                    .source(fromAddress)
                    .destination(Destination.builder().toAddresses(to).build())
                    .message(
                        Message.builder()
                            .subject(Content.builder().data(subject).charset("UTF-8").build())
                            .body(
                                Body.builder()
                                    .html(Content.builder().data(htmlBody).charset("UTF-8").build())
                                    .build(),
                            )
                            .build(),
                    )
                    .build()
            sesClient.sendEmail(request)
        } catch (e: Exception) {
            logger.error("[MailService] 발송 실패(to=$to): ${e.message}")
        }
    }
}
