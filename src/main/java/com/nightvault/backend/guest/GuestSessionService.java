package com.nightvault.backend.guest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class GuestSessionService {
    static final Duration SESSION_LIFETIME = Duration.ofDays(30);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final JdbcClient jdbc;
    private final Clock clock;

    GuestSessionService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Optional<SessionCookie> start(String existingToken, boolean restart) {
        var existing = refresh(existingToken);
        if (existing.isPresent() || (existingToken != null && !restart)) {
            return existing;
        }
        return Optional.of(issue("REGULAR"));
    }

    /**
     * 시연 게스트 세션(DEMO-01). 현재 쿠키가 유효한 시연 게스트면 갱신하고, 아니면 새 시연 게스트를 발급한다.
     * 일반 게스트 쿠키는 재사용하지 않으므로 브라우저의 쿠키가 시연 게스트로 바뀐다.
     */
    @Transactional
    public SessionCookie startDemo(String existingToken) {
        if (isToken(existingToken)) {
            boolean demo = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM guest_sessions s JOIN guests g ON g.id = s.guest_id
                               WHERE s.token_hash = :hash AND g.kind = 'DEMO')
                """).param("hash", hash(existingToken)).query(Boolean.class).single();
            if (demo) {
                var existing = refresh(existingToken);
                if (existing.isPresent()) {
                    return existing.get();
                }
            }
        }
        return issue("DEMO");
    }

    private SessionCookie issue(String kind) {
        var now = OffsetDateTime.now(clock);
        var guestId = jdbc.sql("INSERT INTO guests (created_at, kind) VALUES (:now, :kind) RETURNING id")
            .param("now", now).param("kind", kind).query(UUID.class).single();
        jdbc.sql("INSERT INTO guest_settings (guest_id) VALUES (:guestId)")
            .param("guestId", guestId).update();

        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        var expiresAt = now.plus(SESSION_LIFETIME);

        Instant storedExpiry = jdbc.sql("""
            INSERT INTO guest_sessions (guest_id, token_hash, expires_at)
            VALUES (:guestId, :hash, :expiresAt)
            RETURNING expires_at
            """)
            .param("guestId", guestId)
            .param("hash", hash(token))
            .param("expiresAt", expiresAt)
            .query((row, rowNumber) -> row.getObject("expires_at", OffsetDateTime.class).toInstant())
            .single();
        return new SessionCookie(token, storedExpiry, true);
    }

    Optional<SessionCookie> refresh(String token) {
        if (!isToken(token)) {
            return Optional.empty();
        }
        var now = OffsetDateTime.now(clock);
        return jdbc.sql("""
            UPDATE guest_sessions AS session
            SET expires_at = GREATEST(session.expires_at, :expiresAt)
            FROM guests AS guest
            WHERE session.guest_id = guest.id
              AND guest.state = 'ACTIVE'
              AND session.token_hash = :hash
              AND session.expires_at > :now
            RETURNING session.expires_at
            """)
            .param("expiresAt", now.plus(SESSION_LIFETIME))
            .param("hash", hash(token))
            .param("now", now)
            .query((row, rowNumber) -> new SessionCookie(
                token, row.getObject("expires_at", OffsetDateTime.class).toInstant(), false))
            .optional();
    }

    Optional<UUID> authenticate(String token) {
        if (!isToken(token)) {
            return Optional.empty();
        }
        return jdbc.sql("""
            SELECT session.guest_id
            FROM guest_sessions AS session
            JOIN guests AS guest ON guest.id = session.guest_id
            WHERE guest.state = 'ACTIVE'
              AND session.token_hash = :hash
              AND session.expires_at > :now
            """)
            .param("hash", hash(token))
            .param("now", OffsetDateTime.now(clock))
            .query(UUID.class).optional();
    }

    private static boolean isToken(String token) {
        return token != null && TOKEN_FORMAT.matcher(token).matches();
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record SessionCookie(String token, Instant expiresAt, boolean issued) {}
}
