package com.nightvault.backend.analysis;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AnalysisJobStore {
    private static final int MAX_ATTEMPTS = 3;
    private static final int LEASE_SECONDS = 8;

    private final JdbcClient jdbc;
    private final AnalysisPayloads payloads;

    AnalysisJobStore(JdbcClient jdbc, AnalysisPayloads payloads) {
        this.jdbc = jdbc;
        this.payloads = payloads;
    }

    void finalizeStaleRequests() {
        jdbc.sql("""
            WITH stale AS (
                SELECT r.id, CASE
                    WHEN r.expires_at <= statement_timestamp() OR g.state <> 'ACTIVE'
                         OR r.state = 'SUPPORT_GUIDANCE' THEN 'CANCELLED'
                    ELSE 'FAILED'
                END AS final_state
                FROM analysis_requests r
                JOIN guests g ON g.id = r.guest_id
                WHERE ((r.state IN ('QUEUED', 'RUNNING') AND
                        (r.expires_at <= statement_timestamp() OR g.state <> 'ACTIVE'))
                    OR (r.state = 'RUNNING' AND r.lease_until <= statement_timestamp()
                        AND r.attempt_count >= :limit)
                    OR (r.state = 'SUPPORT_GUIDANCE' AND r.guidance_until <= statement_timestamp()))
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
            """).param("limit", MAX_ATTEMPTS).update();
    }

    Optional<Job> claim() {
        return jdbc.sql("""
            WITH candidate AS (
                SELECT r.id
                FROM analysis_requests r
                JOIN guests g ON g.id = r.guest_id AND g.state = 'ACTIVE'
                WHERE r.expires_at > statement_timestamp()
                  AND r.attempt_count < :limit
                  AND ((r.state = 'QUEUED' AND r.next_attempt_at <= statement_timestamp())
                       OR (r.state = 'RUNNING' AND r.lease_until <= statement_timestamp()))
                ORDER BY r.next_attempt_at, r.id
                LIMIT 1
                FOR UPDATE OF r SKIP LOCKED
            )
            UPDATE analysis_requests r
            SET state = 'RUNNING', attempt_id = gen_random_uuid(), attempt_count = r.attempt_count + 1,
                lease_until = statement_timestamp() + :leaseSeconds * INTERVAL '1 second'
            FROM candidate WHERE r.id = candidate.id
            RETURNING r.id, r.guest_id, r.attempt_id, r.input_ciphertext
            """)
            .param("limit", MAX_ATTEMPTS).param("leaseSeconds", LEASE_SECONDS)
            .query((row, rowNumber) -> new Job(row.getObject("id", UUID.class),
                row.getObject("guest_id", UUID.class), row.getObject("attempt_id", UUID.class),
                row.getBytes("input_ciphertext")))
            .optional();
    }

    @Transactional
    public void finish(Job job, Result result) {
        // Use the same guest-first lock order as submit/retry and future vault deletion.
        if (jdbc.sql("SELECT id FROM guests WHERE id = :id AND state = 'ACTIVE' FOR UPDATE")
            .param("id", job.guestId()).query(UUID.class).optional().isEmpty()) {
            return;
        }
        var current = jdbc.sql("""
            SELECT attempt_count, accepted_at, expires_at, publish_at
            FROM analysis_requests
            WHERE id = :id AND guest_id = :guestId AND state = 'RUNNING' AND attempt_id = :attemptId
                AND lease_until > clock_timestamp() AND expires_at > clock_timestamp()
            FOR UPDATE
            """).param("id", job.requestId()).param("guestId", job.guestId()).param("attemptId", job.attemptId())
            .query((row, rowNumber) -> new CurrentAttempt(row.getInt("attempt_count"),
                row.getObject("accepted_at", OffsetDateTime.class), row.getObject("expires_at", OffsetDateTime.class),
                row.getObject("publish_at", OffsetDateTime.class)))
            .optional();
        if (current.isEmpty()) {
            return;
        }

        var attempt = current.get();
        switch (result.outcome()) {
            case SUCCESS -> seal(job, attempt, result.content());
            case SUPPORT_GUIDANCE -> completeWithoutWorry(job.requestId(), "SUPPORT_GUIDANCE");
            case FAILURE -> completeWithoutWorry(job.requestId(), "FAILED");
            case TIMEOUT -> {
                if (attempt.count() >= MAX_ATTEMPTS) {
                    completeWithoutWorry(job.requestId(), "FAILED");
                } else {
                    jdbc.sql("""
                        UPDATE analysis_requests
                        SET state = 'QUEUED', attempt_id = NULL, lease_until = NULL,
                            next_attempt_at = clock_timestamp() + INTERVAL '1 second'
                        WHERE id = :id
                        """).param("id", job.requestId()).update();
                }
            }
        }
    }

    private void seal(Job job, CurrentAttempt attempt, AnalysisContent content) {
        UUID worryId = jdbc.sql("""
            INSERT INTO worries (guest_id, request_id, created_at, sealed_at, publish_at, expires_at, input_ciphertext)
            VALUES (:guestId, :requestId, :createdAt, clock_timestamp(), :publishAt, :expiresAt, :input)
            RETURNING id
            """)
            .param("guestId", job.guestId()).param("requestId", job.requestId())
            .param("createdAt", attempt.acceptedAt()).param("publishAt", attempt.publishAt())
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

    private void completeWithoutWorry(UUID requestId, String state) {
        jdbc.sql("""
            UPDATE analysis_requests
            SET state = :state, attempt_id = NULL, lease_until = NULL, publish_at = NULL, input_ciphertext = NULL,
                submission_fingerprint = NULL,
                context_hash = CASE WHEN :state = 'SUPPORT_GUIDANCE' THEN context_hash END,
                guidance_until = CASE WHEN :state = 'SUPPORT_GUIDANCE' THEN clock_timestamp() + INTERVAL '15 minutes' END
            WHERE id = :id
            """).param("state", state).param("id", requestId).update();
        jdbc.sql("UPDATE analysis_retries SET fingerprint = NULL WHERE request_id = :id")
            .param("id", requestId).update();
    }

    record Job(UUID requestId, UUID guestId, UUID attemptId, byte[] encryptedInput) {}
    record Result(Outcome outcome, AnalysisContent content) {}

    enum Outcome { SUCCESS, SUPPORT_GUIDANCE, FAILURE, TIMEOUT }

    private record CurrentAttempt(int count, OffsetDateTime acceptedAt, OffsetDateTime expiresAt, OffsetDateTime publishAt) {}
}
