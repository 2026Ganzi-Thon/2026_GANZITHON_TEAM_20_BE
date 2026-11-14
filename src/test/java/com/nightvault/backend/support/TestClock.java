package com.nightvault.backend.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

/**
 * 테스트용 서버 시각. 멈춰 있는 시계이며 {@link #advance}로만 움직인다(남은 초 계산이 실제 경과 시간에 흔들리지 않게).
 * 운영 {@code Clock} 빈 대신 주입되며({@code @Primary}), {@link IntegrationTest}가 테스트마다 실제 현재 시각으로 맞춘다.
 *
 * <p>픽스처의 걱정 시각은 실제 시각 기준이므로, 시계를 하루 넘기면 "잠김 → 공개", 30일 넘기면 "만료"가 된다.</p>
 */
@TestComponent
@Primary
public class TestClock extends Clock {
    private volatile Instant current = WorryFixtures.now();

    public synchronized void advance(Duration amount) {
        current = current.plus(amount);
    }

    public void reset() {
        current = WorryFixtures.now();
    }

    @Override
    public Instant instant() {
        return current;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }
}
