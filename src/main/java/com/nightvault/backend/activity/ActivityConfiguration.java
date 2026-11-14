package com.nightvault.backend.activity;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ActivityConfiguration {
    /**
     * 활동 규칙이 쓰는 서버 시각. 시연 타임머신·테스트에서 바꿔 끼울 수 있게 빈으로 둔다.
     * 공개·만료 판정과 휴식 타이머 모두 이 시각을 기준으로 한다(DB {@code statement_timestamp()}를 쓰지 않는다).
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
