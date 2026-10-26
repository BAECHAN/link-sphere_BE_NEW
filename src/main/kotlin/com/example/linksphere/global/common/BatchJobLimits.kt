package com.example.linksphere.global.common

/**
 * EventBridge가 부르는 배치 잡(계정 퍼지·피드 크롤링·고아 이미지 정리)이 공유하는 시간 한도. Lambda 타임아웃이 120초라
 * (docs/DEPLOY.md), 잡은 90초가 지나면 남은 항목을 다음 실행으로 넘기고 요약 로그를 남긴 채 끝낸다 — 외부 호출 하나가 마지막
 * 순간에 걸려도(Supabase 읽기 타임아웃 10초 + 연결 3초) 120초 안에 끝난다(docs/BE-ARCHITECTURE.md §7-7).
 */
object BatchJobLimits {

    const val DEADLINE_MILLIS = 90_000L
}
