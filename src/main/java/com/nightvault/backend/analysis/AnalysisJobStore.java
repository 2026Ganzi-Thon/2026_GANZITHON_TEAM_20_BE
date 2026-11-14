package com.nightvault.backend.analysis;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.nightvault.backend.guest.GuestWriteLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AnalysisJobStore {
    private static final int MAX_ATTEMPTS = 3;
    private static final int LEASE_GRACE_SECONDS = 5;

    private final JdbcClient jdbc;
    private final GuestWriteLock guestWriteLock;
    private final AnalysisPayloads payloads;
    private final Clock clock;

    AnalysisJobStore(JdbcClient jdbc, AnalysisPayloads payloads, Clock clock, GuestWriteLock guestWriteLock) {
        this.jdbc = jdbc;
        this.guestWriteLock = guestWriteLock;
        this.payloads = payloads;
        this.clock = clock;
    }

    void finalizeStaleRequests() {
        jdbc.sql("""
            WITH stale AS (
                SELECT r.id, CASE
                    WHEN r.expires_at <= :now OR g.state <> 'ACTIVE'
                         OR r.state = 'SUPPORT_GUIDANCE' THEN 'CANCELLED'
                    ELSE 'FAILED'
                END AS final_state
                FROM analysis_requests r
                JOIN guests g ON g.id = r.guest_id
                WHERE ((r.state IN ('QUEUED', 'RUNNING') AND
                        (r.expires_at <= :now OR g.state <> 'ACTIVE'))
                    OR (r.state = 'RUNNING' AND r.lease_until <= :now
                        AND r.attempt_count >= :limit)
                    OR (r.state = 'SUPPORT_GUIDANCE' AND r.guidance_until <= :now))
                ORDER BY r.accepted_at, r.id
                LIMIT 100
                FOR UPDATE OF r SKIP LOCKED
            ), finalized AS (
                UPDATE analysis_requests r
                SET state = stale.final_state, attempt_id = NULL, lease_until = NULL, publish_at = NULL,
                    input_ciphertext = NULL, context_hash = NULL, guidance_until = NULL, submission_fingerprint = NULL
                FROM stale WHERE r.id = stale.id
                RETURNING r.id
            )
            UPDATE analysis_retries SET fingerprint = NULL
            WHERE request_id IN (SELECT id FROM finalized)
            """).param("limit", MAX_ATTEMPTS).param("now", OffsetDateTime.now(clock)).update();
    }

    Optional<Job> claim(int timeoutSeconds) {
        var now = OffsetDateTime.now(clock);
        return jdbc.sql("""
            WITH candidate AS (
                SELECT r.id
                FROM analysis_requests r
                JOIN guests g ON g.id = r.guest_id AND g.state = 'ACTIVE'
                WHERE r.expires_at > :now
                  AND r.attempt_count < :limit
                  AND ((r.state = 'QUEUED' AND r.next_attempt_at <= :now)
                       OR (r.state = 'RUNNING' AND r.lease_until <= :now))
                ORDER BY r.next_attempt_at, r.id
                LIMIT 1
                FOR UPDATE OF r SKIP LOCKED
            )
            UPDATE analysis_requests r
            SET state = 'RUNNING', attempt_id = gen_random_uuid(), attempt_count = r.attempt_count + 1,
                lease_until = :leaseUntil
            FROM candidate WHERE r.id = candidate.id
            RETURNING r.id, r.guest_id, r.attempt_id, r.input_ciphertext
            """)
            .param("limit", MAX_ATTEMPTS).param("now", now)
            .param("leaseUntil", now.plusSeconds(timeoutSeconds + LEASE_GRACE_SECONDS))
            .query((row, rowNumber) -> new Job(row.getObject("id", UUID.class),
                row.getObject("guest_id", UUID.class), row.getObject("attempt_id", UUID.class),
                row.getBytes("input_ciphertext")))
            .optional();
    }

    @Transactional
    public void finish(Job job, Result result) {
        // Use the same guest-first lock order as submit/retry and future vault deletion.
        if (!guestWriteLock.lockActive(job.guestId())) {
            return;
        }
        var current = jdbc.sql("""
            SELECT attempt_count, accepted_at, expires_at, publish_at, lease_until
            FROM analysis_requests
            WHERE id = :id AND guest_id = :guestId AND state = 'RUNNING' AND attempt_id = :attemptId
            FOR UPDATE
            """).param("id", job.requestId()).param("guestId", job.guestId()).param("attemptId", job.attemptId())
            .query((row, rowNumber) -> new CurrentAttempt(row.getInt("attempt_count"),
                row.getObject("accepted_at", OffsetDateTime.class), row.getObject("expires_at", OffsetDateTime.class),
                row.getObject("publish_at", OffsetDateTime.class), row.getObject("lease_until", OffsetDateTime.class)))
            .optional();
        if (current.isEmpty()) {
            return;
        }

        var attempt = current.get();
        var now = OffsetDateTime.now(clock);
        if (!attempt.leaseUntil().isAfter(now) || !attempt.expiresAt().isAfter(now)) {
            return;
        }
        switch (result.outcome()) {
            case SUCCESS -> seal(job, attempt, result.content(), now);
            case SUPPORT_GUIDANCE -> completeWithoutWorry(job.requestId(), "SUPPORT_GUIDANCE", now);
            case FAILURE -> completeWithoutWorry(job.requestId(), "FAILED", now);
            case RETRYABLE_FAILURE -> {
                if (attempt.count() >= MAX_ATTEMPTS) {
                    completeWithoutWorry(job.requestId(), "FAILED", now);
                } else {
                    jdbc.sql("""
                        UPDATE analysis_requests
                        SET state = 'QUEUED', attempt_id = NULL, lease_until = NULL,
                            next_attempt_at = :nextAttemptAt
                        WHERE id = :id
                        """).param("id", job.requestId()).param("nextAttemptAt", now.plusSeconds(1)).update();
                }
            }
        }
    }

    private void seal(Job job, CurrentAttempt attempt, AnalysisContent content, OffsetDateTime now) {
        UUID worryId = jdbc.sql("""
            INSERT INTO worries (guest_id, request_id, created_at, sealed_at, publish_at, expires_at, input_ciphertext)
            VALUES (:guestId, :requestId, :createdAt, :now, :publishAt, :expiresAt, :input)
            RETURNING id
            """)
            .param("guestId", job.guestId()).param("requestId", job.requestId())
            .param("createdAt", attempt.acceptedAt()).param("now", now).param("publishAt", attempt.publishAt())
            .param("expiresAt", attempt.expiresAt()).param("input", job.encryptedInput())
            .query(UUID.class).single();
        jdbc.sql("INSERT INTO ai_contents (worry_id, content_ciphertext) VALUES (:id, :content)")
            .param("id", worryId).param("content", payloads.encryptContent(job.requestId(), content)).update();
        jdbc.sql("""
            UPDATE analysis_requests
            SET state = 'SUCCEEDED', attempt_id = NULL, lease_until = NULL, input_ciphertext = NULL, context_hash = NULL
            WHERE id = :id
            """).param("id", job.requestId()).update();
    }

    private void completeWithoutWorry(UUID requestId, String state, OffsetDateTime now) {
        jdbc.sql("""
            UPDATE analysis_requests
            SET state = :state, attempt_id = NULL, lease_until = NULL, publish_at = NULL, input_ciphertext = NULL,
                submission_fingerprint = NULL,
                context_hash = CASE WHEN :state = 'SUPPORT_GUIDANCE' THEN context_hash END,
                guidance_until = CASE WHEN :state = 'SUPPORT_GUIDANCE' THEN :guidanceUntil END
            WHERE id = :id
            """).param("state", state).param("id", requestId).param("guidanceUntil", now.plusMinutes(15)).update();
        jdbc.sql("UPDATE analysis_retries SET fingerprint = NULL WHERE request_id = :id")
            .param("id", requestId).update();
    }

    record Job(UUID requestId, UUID guestId, UUID attemptId, byte[] encryptedInput) {}
    record Result(Outcome outcome, AnalysisContent content) {}

    enum Outcome { SUCCESS, SUPPORT_GUIDANCE, FAILURE, RETRYABLE_FAILURE }

    private record CurrentAttempt(int count, OffsetDateTime acceptedAt, OffsetDateTime expiresAt,
                                  OffsetDateTime publishAt, OffsetDateTime leaseUntil) {}
}
