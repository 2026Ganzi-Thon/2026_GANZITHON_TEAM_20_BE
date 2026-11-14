package com.nightvault.backend.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

/**
 * 테스트용 서버 시각. 실제 시각에 {@link #advance}로 더한 만큼 앞서 간다.
 * 운영 {@code Clock} 빈 대신 주입되며({@code @Primary}), {@link IntegrationTest}가 테스트마다 0으로 되돌린다.
 *
 * <p>픽스처의 걱정 시각은 실제 시각 기준이므로, 시계를 하루 넘기면 "잠김 → 공개", 30일 넘기면 "만료"가 된다.</p>
 */
@TestComponent
@Primary
public class TestClock extends Clock {
    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration amount) {
        offset = offset.plus(amount);
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
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
