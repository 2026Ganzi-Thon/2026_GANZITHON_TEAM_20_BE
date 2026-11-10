package com.nightvault.backend.analysis;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AnalysisRequests {
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final Duration FAILED_VISIBILITY = Duration.ofHours(24);
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final String REQUEST_STATE = """
        SELECT r.id, r.state, r.accepted_at, r.expires_at, r.submission_fingerprint,
               r.context_hash, r.guidance_until, w.id AS worry_id, w.sealed_at, w.publish_at
        FROM analysis_requests r
        JOIN guests g ON g.id = r.guest_id AND g.state = 'ACTIVE'
        LEFT JOIN worries w ON w.request_id = r.id AND w.deletion_started_at IS NULL
            AND w.expires_at > statement_timestamp()
        WHERE r.guest_id = :guestId
        """;

    private final JdbcClient jdbc;
    private final AnalysisPayloads payloads;

    AnalysisRequests(JdbcClient jdbc, AnalysisPayloads payloads) {
        this.jdbc = jdbc;
        this.payloads = payloads;
    }

    @Transactional
    public Submission submit(UUID guestId, UUID key, UUID context, WorryInput input) {
        lockGuest(guestId);
        var now = now();
        var existing = requestQuery(" AND r.submission_key = :key")
            .param("guestId", guestId).param("key", key).query(AnalysisRequests::readRequest).optional();
        if (existing.isPresent()) {
            var request = existing.get();
            return replay(request, request.submissionFingerprint(), input, context, now.toInstant());
        }

        var expiresAt = now.plus(RETENTION);
        WorryInput.validatePublication(input.publishAt(), now.toInstant(), expiresAt.toInstant());
        UUID requestId = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO analysis_requests
                (id, guest_id, submission_key, submission_fingerprint, accepted_at, expires_at, publish_at,
                 input_ciphertext, context_hash, next_attempt_at)
            VALUES (:id, :guestId, :key, :fingerprint, :now, :expiresAt, :publishAt, :input, :context, :now)
            """)
            .param("id", requestId).param("guestId", guestId).param("key", key)
            .param("fingerprint", payloads.fingerprint(input)).param("now", now).param("expiresAt", expiresAt)
            .param("publishAt", utc(input.publishAt())).param("input", payloads.encryptInput(requestId, input))
            .param("context", payloads.contextHash(context)).update();
        return new Submission(AnalysisStatus.pending(requestId, now.toInstant()), true);
    }

    AnalysisStatus get(UUID guestId, UUID requestId, UUID context) {
        return status(find(guestId, requestId), context, now().toInstant());
    }

    @Transactional
    public Submission retry(UUID guestId, UUID requestId, UUID key, UUID context, WorryInput input) {
        lockGuest(guestId);
        var now = now();
        var request = find(guestId, requestId);
        var retry = jdbc.sql("""
            SELECT fingerprint FROM analysis_retries WHERE request_id = :id AND retry_key = :key
            """).param("id", requestId).param("key", key)
            .query((row, rowNumber) -> new Retry(row.getBytes("fingerprint"))).optional();
        if (retry.isPresent()) {
            return replay(request, retry.get().fingerprint(), input, context, now.toInstant());
        }

        var current = status(request, context, now.toInstant());
        if (!current.status().equals("FAILED")) {
            throw new AnalysisException(ApiError.RETRY_NOT_ALLOWED);
        }
        WorryInput.validatePublication(input.publishAt(), now.toInstant(), request.expiresAt());
        jdbc.sql("""
            INSERT INTO analysis_retries (request_id, retry_key, accepted_at, fingerprint)
            VALUES (:id, :key, :now, :fingerprint)
            """)
            .param("id", requestId).param("key", key).param("now", now)
            .param("fingerprint", payloads.fingerprint(input)).update();
        jdbc.sql("""
            UPDATE analysis_requests
            SET state = 'QUEUED', publish_at = :publishAt, input_ciphertext = :input, context_hash = :context,
                guidance_until = NULL, attempt_count = 0, next_attempt_at = :now
            WHERE id = :id
            """)
            .param("publishAt", utc(input.publishAt())).param("input", payloads.encryptInput(requestId, input))
            .param("context", payloads.contextHash(context)).param("now", now).param("id", requestId).update();
        return new Submission(AnalysisStatus.pending(requestId, request.acceptedAt()), true);
    }

    DefaultPublication defaultPublication(UUID guestId, String timeZone) {
        if (timeZone.length() > 100 || !ZoneId.getAvailableZoneIds().contains(timeZone)) {
            throw new AnalysisException(ApiError.INVALID_INPUT);
        }
        var zone = ZoneId.of(timeZone);
        LocalTime time = jdbc.sql("""
            SELECT default_open_local_time FROM guest_settings WHERE guest_id = :guestId
            """).param("guestId", guestId)
            .query((row, rowNumber) -> row.getObject("default_open_local_time", LocalTime.class))
            .optional().orElse(LocalTime.of(8, 0));
        Instant now = now().toInstant();
        var rules = zone.getRules();
        for (var date = now.atZone(zone).toLocalDate(); ; date = date.plusDays(1)) {
            LocalDateTime local = date.atTime(time);
            var offsets = rules.getValidOffsets(local);
            if (offsets.isEmpty()) {
                var afterGap = rules.getTransition(local).getInstant();
                if (afterGap.isAfter(now)) {
                    return new DefaultPublication(afterGap, timeZone, time.format(LOCAL_TIME));
                }
            } else {
                var future = offsets.stream().map(local::toInstant).filter(value -> value.isAfter(now)).min(Instant::compareTo);
                if (future.isPresent()) {
                    return new DefaultPublication(future.get(), timeZone, time.format(LOCAL_TIME));
                }
            }
        }
    }

    private RequestRow find(UUID guestId, UUID requestId) {
        return requestQuery(" AND r.id = :id").param("guestId", guestId).param("id", requestId)
            .query(AnalysisRequests::readRequest).optional()
            .orElseThrow(() -> new AnalysisException(ApiError.NOT_FOUND));
    }

    private JdbcClient.StatementSpec requestQuery(String condition) {
        return jdbc.sql(REQUEST_STATE + condition);
    }

    private void lockGuest(UUID guestId) {
        // Serializes submit/retry per guest and matches the worker's guest-first lock order.
        jdbc.sql("SELECT id FROM guests WHERE id = :id AND state = 'ACTIVE' FOR UPDATE")
            .param("id", guestId).query(UUID.class).optional()
            .orElseThrow(() -> new AnalysisException(ApiError.GUEST_SESSION_INVALID));
    }

    private Submission replay(RequestRow request, byte[] fingerprint, WorryInput input, UUID context, Instant now) {
        AnalysisStatus current = status(request, context, now);
        if (fingerprint != null && !MessageDigest.isEqual(fingerprint, payloads.fingerprint(input))) {
            throw new AnalysisException(ApiError.IDEMPOTENCY_CONFLICT);
        }
        return new Submission(current, false);
    }

    private AnalysisStatus status(RequestRow request, UUID context, Instant now) {
        if (!now.isBefore(request.expiresAt())) {
            throw new AnalysisException(ApiError.NOT_FOUND);
        }
        return switch (request.state()) {
            case "QUEUED", "RUNNING" -> AnalysisStatus.pending(request.id(), request.acceptedAt());
            case "SUCCEEDED" -> {
                if (request.worryId() == null) {
                    throw new AnalysisException(ApiError.NOT_FOUND);
                }
                yield AnalysisStatus.succeeded(request.id(), request.acceptedAt(), request.worryId(),
                    request.sealedAt(), request.publishAt());
            }
            case "FAILED" -> {
                if (!now.isBefore(request.acceptedAt().plus(FAILED_VISIBILITY))) {
                    throw new AnalysisException(ApiError.NOT_FOUND);
                }
                yield AnalysisStatus.failed(request.id(), request.acceptedAt());
            }
            case "SUPPORT_GUIDANCE" -> {
                if (context == null || request.guidanceUntil() == null || !now.isBefore(request.guidanceUntil())
                    || !MessageDigest.isEqual(request.contextHash(), payloads.contextHash(context))) {
                    throw new AnalysisException(ApiError.NOT_FOUND);
                }
                yield AnalysisStatus.guidance(request.id(), request.acceptedAt());
            }
            default -> throw new AnalysisException(ApiError.NOT_FOUND);
        };
    }

    private static RequestRow readRequest(java.sql.ResultSet row, int rowNumber) throws java.sql.SQLException {
        return new RequestRow(row.getObject("id", UUID.class), row.getString("state"),
            instant(row, "accepted_at"), instant(row, "expires_at"), row.getBytes("submission_fingerprint"),
            row.getBytes("context_hash"), instant(row, "guidance_until"), row.getObject("worry_id", UUID.class),
            instant(row, "sealed_at"), instant(row, "publish_at"));
    }

    private static Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime now() {
        return utc(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    record Submission(AnalysisStatus status, boolean created) {}
    record DefaultPublication(Instant publishAt, String timeZone, String localTime) {}

    private record Retry(byte[] fingerprint) {}

    private record RequestRow(
        UUID id, String state, Instant acceptedAt, Instant expiresAt, byte[] submissionFingerprint,
        byte[] contextHash, Instant guidanceUntil, UUID worryId, Instant sealedAt, Instant publishAt
    ) {}
}
