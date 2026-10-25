package com.example.linksphere.infra.fcm

import com.example.linksphere.support.PostgresIntegrationTest
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

// 댓글 후처리 잡(CommentPostProcessService.processCommentJob)은 한 트랜잭션 안에서 알림(FcmService.sendToUser)과 링크
// 프리뷰 갱신을 한다. FcmService가 그 트랜잭션에 참여한 채 DB 작업에 실패하면, 예외는 FcmService가 삼켜도 Hibernate가 그
// 트랜잭션을 롤백 전용으로 표시해 커밋이 UnexpectedRollbackException으로 실패한다 - 링크 프리뷰까지 사라지고 Lambda
// 비동기 재시도로 알림이 다시 나간다. FCM의 DB 작업을 별도 트랜잭션(REQUIRES_NEW)으로 떼어 놓았는지 실제 Postgres로 본다.
//
// DB 오류는 FcmTokenRepository mock이 현재 트랜잭션 연결에서 실패하는 SQL을 실행해 만든다. 커밋 동작을 봐야 하므로 테스트
// 트랜잭션 밖에서 돌고(MemberSessionServiceIntegrationTest와 같은 이유), 끝나면 넣은 회원을 지운다.
@PostgresIntegrationTest
@Import(FcmService::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FcmServiceIntegrationTest {

    @Autowired private lateinit var fcmService: FcmService

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var entityManager: EntityManager

    @MockitoBean private lateinit var fcmTokenRepository: FcmTokenRepository

    @MockitoBean private lateinit var fcmTokenService: FcmTokenService

    private val memberId = UUID.randomUUID()

    private lateinit var firebaseApp: FirebaseApp

    @BeforeEach
    fun setUp() {
        // FcmService는 FirebaseApp이 없으면 바로 끝낸다 - 오프라인 가짜 자격증명으로 앱만 만든다(발송까지는 가지 않는다)
        firebaseApp =
            FirebaseApp.initializeApp(
                FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.create(AccessToken("test-token", null)))
                    .setProjectId("link-sphere-test")
                    .build(),
                "fcm-service-integration-test",
            )
        doAnswer {
            entityManager.createNativeQuery("SELECT 1/0").singleResult
            0
        }.`when`(fcmTokenRepository).deleteStaleTokensForUser(memberId)
    }

    @AfterEach
    fun tearDown() {
        firebaseApp.delete()
        jdbc.update("DELETE FROM members WHERE id = ?", memberId)
    }

    @Test
    fun `FCM 토큰 정리가 DB 오류로 실패해도 같은 잡의 다른 쓰기는 커밋된다`() {
        TransactionTemplate(transactionManager).executeWithoutResult {
            jdbc.update("INSERT INTO members (id) VALUES (?)", memberId)
            // processCommentJob이 알림을 runCatching으로 감싸는 것과 같은 형태
            runCatching { fcmService.sendToUser(memberId, "새 댓글", "내용") }
        }

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM members WHERE id = ?", Int::class.java, memberId))
    }
}
