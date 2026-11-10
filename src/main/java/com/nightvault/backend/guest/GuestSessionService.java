package com.nightvault.backend.guest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    GuestSessionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Optional<SessionCookie> start(String existingToken, boolean restart) {
        var existing = refresh(existingToken);
        if (existing.isPresent() || (existingToken != null && !restart)) {
            return existing;
        }

        var guestId = jdbc.sql("INSERT INTO guests DEFAULT VALUES RETURNING id")
            .query(UUID.class).single();
        jdbc.sql("INSERT INTO guest_settings (guest_id) VALUES (:guestId)")
            .param("guestId", guestId).update();

        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        var expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(SESSION_LIFETIME);

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
        return Optional.of(new SessionCookie(token, storedExpiry, true));
    }

    Optional<SessionCookie> refresh(String token) {
        if (!isToken(token)) {
            return Optional.empty();
        }
        var now = OffsetDateTime.now(ZoneOffset.UTC);
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
            .param("now", OffsetDateTime.now(ZoneOffset.UTC))
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
