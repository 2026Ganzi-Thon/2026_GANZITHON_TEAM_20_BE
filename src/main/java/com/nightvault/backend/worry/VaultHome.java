package com.nightvault.backend.worry;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class VaultHome {
    private final JdbcClient jdbc;
    private final Clock clock;

    VaultHome(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Summary get(UUID guestId) {
        var now = OffsetDateTime.now(clock);
        var counts = jdbc.sql("""
            SELECT count(*) FILTER (WHERE w.publish_at > :now) AS locked,
                   count(*) FILTER (WHERE w.publish_at <= :now) AS public_count,
                   min(w.publish_at) FILTER (WHERE w.publish_at > :now) AS nearest
            FROM worries w JOIN guests g ON g.id = w.guest_id AND g.state = 'ACTIVE'
            WHERE w.guest_id = :guestId AND w.deletion_started_at IS NULL AND w.expires_at > :now
            """).param("now", now).param("guestId", guestId)
            .query((row, index) -> {
                var nearest = row.getObject("nearest", OffsetDateTime.class);
                return new Counts(row.getLong("locked"), row.getLong("public_count"),
                    nearest == null ? null : nearest.toInstant());
            }).single();
        var requests = jdbc.sql("""
            SELECT r.id, r.accepted_at, r.state
            FROM analysis_requests r JOIN guests g ON g.id = r.guest_id AND g.state = 'ACTIVE'
            WHERE r.guest_id = :guestId AND r.expires_at > :now
              AND (r.state IN ('QUEUED', 'RUNNING') OR (r.state = 'FAILED' AND r.accepted_at > :cutoff))
            ORDER BY r.accepted_at DESC, r.id DESC
            """).param("now", now).param("guestId", guestId).param("cutoff", now.minusHours(24))
            .query((row, index) -> {
                boolean failed = row.getString("state").equals("FAILED");
                return new RequestCard(row.getObject("id", UUID.class),
                    row.getObject("accepted_at", OffsetDateTime.class).toInstant(),
                    failed ? "FAILED" : "PENDING", failed ? "ANALYSIS_FAILED" : null);
            }).list();
        return new Summary(counts.locked(), counts.published(), counts.nearest(), requests);
    }

    record Summary(long lockedCount, long publicCount, Instant nearestPublishAt, List<RequestCard> analysisRequests) {}
    record RequestCard(UUID requestId, Instant acceptedAt, String status,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String failureCode) {}
    private record Counts(long locked, long published, Instant nearest) {}
}
