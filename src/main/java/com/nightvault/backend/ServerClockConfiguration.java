package com.nightvault.backend;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ServerClockConfiguration {
    @Bean
    Clock serverClock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
