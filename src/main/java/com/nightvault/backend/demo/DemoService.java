package com.nightvault.backend.demo;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
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

    /**
     * 타임머신(DEMO-02). 서버 시계는 그대로 두고, 시연 게스트의 시각 컬럼을 모두 같은 폭만큼 과거로 당긴다.
     * 같은 폭으로 옮기므로 {@code worries_publication_check} 같은 시각 간 제약과 순서가 유지된다.
     * 세션 만료 시각은 옮기지 않는다(시연 중 로그아웃 방지).
     */
    @Transactional
    public TimeMachineResult timeMachine(String key, UUID guestId, TimeMachinePreset preset, UUID worryId) {
        UUID guest = access.lockDemoGuest(key, guestId);
        var now = OffsetDateTime.now(clock);
        Duration shift = switch (preset) {
            case TO_PUBLISH -> untilPublish(guest, worryId, now);
            case PLUS_24H -> Duration.ofHours(24);
        };
        shiftGuestTimes(guest, shift);
        return new TimeMachineResult(shift.toSeconds() + (shift.toNanosPart() > 0 ? 1 : 0), currentStatus(guest));
    }

    private Duration untilPublish(UUID guest, UUID worryId, OffsetDateTime now) {
        var target = jdbc.sql("""
            SELECT w.publish_at FROM worries w
            WHERE w.guest_id = :guest AND w.deletion_started_at IS NULL AND w.expires_at > :now
              AND (CAST(:worry AS uuid) IS NULL OR w.id = :worry)
            ORDER BY (w.publish_at > :now) DESC, w.publish_at LIMIT 1
            """).param("guest", guest).param("now", now).param("worry", worryId)
            .query(OffsetDateTime.class).optional();
        if (target.isEmpty()) {
            throw new BusinessException(worryId == null ? ApiError.NO_LOCKED_WORRY : ApiError.NOT_FOUND);
        }
        if (!target.get().isAfter(now)) {
            throw new BusinessException(worryId == null ? ApiError.NO_LOCKED_WORRY : ApiError.ALREADY_PUBLIC);
        }
        return Duration.between(now, target.get());
    }

    private void shiftGuestTimes(UUID guest, Duration shift) {
        long micros = shift.truncatedTo(ChronoUnit.MICROS).toNanos() / 1_000;
        long days = shift.toDays();
        String by = "(:micros * INTERVAL '1 microsecond')";
        for (String sql : List.of(
            "UPDATE guests SET created_at = created_at - @shift WHERE id = :guest",
            "UPDATE guest_settings SET public_push_since = public_push_since - @shift WHERE guest_id = :guest",
            "UPDATE push_subscriptions SET subscribed_at = subscribed_at - @shift WHERE guest_id = :guest",
            "UPDATE vault_reminders SET send_at = send_at - @shift WHERE guest_id = :guest",
            """
            UPDATE analysis_requests SET accepted_at = accepted_at - @shift, expires_at = expires_at - @shift,
                publish_at = publish_at - @shift, guidance_until = guidance_until - @shift,
                lease_until = lease_until - @shift, next_attempt_at = next_attempt_at - @shift
            WHERE guest_id = :guest
            """,
            """
            UPDATE analysis_retries SET accepted_at = accepted_at - @shift
            WHERE request_id IN (SELECT id FROM analysis_requests WHERE guest_id = :guest)
            """,
            """
            UPDATE worries SET created_at = created_at - @shift, sealed_at = sealed_at - @shift, publish_at = publish_at - @shift,
                first_viewed_at = first_viewed_at - @shift, expires_at = expires_at - @shift,
                deletion_started_at = deletion_started_at - @shift
            WHERE guest_id = :guest
            """,
            """
            UPDATE activities SET started_at = started_at - @shift, last_started_at = last_started_at - @shift,
                state_changed_at = state_changed_at - @shift, ended_at = ended_at - @shift, rest_ends_at = rest_ends_at - @shift
            WHERE guest_id = :guest
            """,
            """
            UPDATE activity_feedback SET answered_at = answered_at - @shift, updated_at = updated_at - @shift
            WHERE activity_id IN (SELECT id FROM activities WHERE guest_id = :guest)
            """,
            """
            UPDATE reflections SET responded_at = responded_at - @shift
            WHERE worry_id IN (SELECT id FROM worries WHERE guest_id = :guest)
            """,
            "UPDATE worry_deletion_receipts SET completed_at = completed_at - @shift WHERE guest_id = :guest"
        )) {
            jdbc.sql(sql.replace("@shift", by)).param("micros", micros).param("guest", guest).update();
        }
        // "오늘은 회고 안 할래요"는 현지 날짜로 저장되므로 지난 날수만큼 옮긴다.
        jdbc.sql("UPDATE reflection_skips SET local_date = local_date - CAST(:days AS integer) WHERE guest_id = :guest")
            .param("days", days).param("guest", guest).update();
    }

    enum TimeMachinePreset { TO_PUBLISH, PLUS_24H }

    record TimeMachineResult(long shiftedSeconds, Status status) {}

    /** {@code demo}는 항상 true. 프런트는 이 값으로 "시연 중" 표시를 띄운다. */
    record Status(boolean demo, long lockedCount, long publicCount, long failedRequestCount,
                  OffsetDateTime nextPublishAt) {}
}
