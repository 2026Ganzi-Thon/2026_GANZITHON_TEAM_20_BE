package com.nightvault.backend;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ServerClockConfiguration {
    @Bean
    Clock serverClock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
