package com.example.linksphere.global.config

import com.zaxxer.hikari.HikariDataSource
import org.crac.Context
import org.crac.Core
import org.crac.Resource
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * SnapStart CRaC 훅 — HikariCP 커넥션 풀 체크포인트/복원 처리.
 *
 * 문제: SnapStart는 JVM 초기화 상태를 스냅샷으로 저장하는데, 이 시점에 HikariCP가
 * 이미 DB 커넥션을 맺고 있으면 그 커넥션 정보가 스냅샷에 포함된다.
 * 복원 후 실제 커넥션은 끊겨 있지만 HikariCP는 살아있다고 착각해 Connection Refused 발생.
 *
 * 해결: beforeCheckpoint에서 풀을 suspend해 커넥션을 닫고,
 *       afterRestore에서 풀을 resume해 신규 커넥션을 맺는다.
 *
 * 전제 조건: application.yml에 hikari.allow-pool-suspension: true 설정 필요.
 */
@Component
class DataSourceCracHook(
    private val dataSource: DataSource
) : Resource {

    init {
        Core.getGlobalContext().register(this)
    }

    override fun beforeCheckpoint(context: Context<out Resource>?) {
        (dataSource as HikariDataSource).hikariPoolMXBean?.suspendPool()
    }

    override fun afterRestore(context: Context<out Resource>?) {
        (dataSource as HikariDataSource).hikariPoolMXBean?.resumePool()
    }
}
