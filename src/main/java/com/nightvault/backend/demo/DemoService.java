package com.nightvault.backend.demo;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 시연 패널(S-18) 서버 기능. 모든 조회·변경은 시연 게스트 한 명의 행으로만 범위를 묶는다. */
@Service
class DemoService {
    private final JdbcClient jdbc;
    private final DemoAccess access;
    private final Clock clock;

    DemoService(JdbcClient jdbc, DemoAccess access, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Status status(String key, UUID guestId) {
        return currentStatus(access.requireDemoGuest(key, guestId));
    }

    Status currentStatus(UUID guest) {
        var now = OffsetDateTime.now(clock);
        return jdbc.sql("""
            SELECT count(*) FILTER (WHERE w.publish_at > :now) AS locked,
                   count(*) FILTER (WHERE w.publish_at <= :now) AS published,
                   min(w.publish_at) FILTER (WHERE w.publish_at > :now) AS next_publish_at,
                   (SELECT count(*) FROM analysis_requests r
                    WHERE r.guest_id = :guest AND r.state = 'FAILED' AND r.expires_at > :now) AS failed
            FROM worries w
            WHERE w.guest_id = :guest AND w.deletion_started_at IS NULL AND w.expires_at > :now
            """).param("guest", guest).param("now", now)
            .query((row, index) -> new Status(true, row.getLong("locked"), row.getLong("published"),
                row.getLong("failed"), row.getObject("next_publish_at", OffsetDateTime.class)))
            .single();
    }

    /** {@code demo}는 항상 true. 프런트는 이 값으로 "시연 중" 표시를 띄운다. */
    record Status(boolean demo, long lockedCount, long publicCount, long failedRequestCount,
                  OffsetDateTime nextPublishAt) {}
}
