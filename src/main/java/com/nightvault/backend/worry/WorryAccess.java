package com.nightvault.backend.worry;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import com.nightvault.backend.guest.GuestWriteLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorryAccess {
    private static final String ACCESSIBLE = """
        JOIN guests g ON g.id = w.guest_id AND g.state = 'ACTIVE'
        WHERE w.guest_id = :guestId AND w.deletion_started_at IS NULL AND w.expires_at > :now
        """;
    private static final String METADATA = """
        SELECT w.id, w.created_at, w.sealed_at, w.publish_at, w.first_viewed_at, w.expires_at
        """;
    private static final String WITH_CONTENT = METADATA + """
             , w.request_id,
               CASE WHEN w.publish_at <= :now THEN w.input_ciphertext END AS input_ciphertext,
               CASE WHEN w.publish_at <= :now THEN w.edited_summary_ciphertext END AS edited_summary_ciphertext,
               (SELECT a.content_ciphertext FROM ai_contents a
                WHERE a.worry_id = w.id AND w.publish_at <= :now) AS content_ciphertext
        """;
    private static final String WITH_DETAIL = WITH_CONTENT + """
        , CASE WHEN w.publish_at <= :now THEN w.anxiety_after_ciphertext END AS anxiety_after_ciphertext
        """;

    private final JdbcClient jdbc;
    private final GuestWriteLock guestWriteLock;
    private final Clock clock;

    WorryAccess(JdbcClient jdbc, Clock clock, GuestWriteLock guestWriteLock) {
        this.jdbc = jdbc;
        this.guestWriteLock = guestWriteLock;
        this.clock = clock;
    }

    public Metadata requirePublic(UUID guestId, UUID worryId) {
        var now = OffsetDateTime.now(clock);
        return requirePublic(findMetadata(guestId, worryId, now), now.toInstant());
    }

    // Keep this lock until the caller's write commits. Guest-first ordering also protects against whole-vault deletion.
    @Transactional(propagation = Propagation.MANDATORY)
    public Metadata lockPublic(UUID guestId, UUID worryId) {
        return lockPublicAt(guestId, worryId).metadata();
    }

    VisibleWorry find(UUID guestId, UUID worryId) {
        var now = OffsetDateTime.now(clock);
        return query(WITH_DETAIL, guestId, now, " AND w.id = :id")
            .param("id", worryId).query((row, number) -> readVisible(row, now.toInstant(), true))
            .optional().orElseThrow(WorryAccess::notFound);
    }

    List<VisibleWorry> page(UUID guestId, boolean published, MetadataCursor cursor, int fetchCount) {
        return page(guestId, published, cursor, fetchCount, false);
    }

    List<VisibleWorry> pastCandidates(UUID guestId, UUID currentId) {
        var now = OffsetDateTime.now(clock);
        return query(WITH_DETAIL, guestId, now, """
             AND w.publish_at <= :now AND w.id <> :current
             ORDER BY w.created_at DESC, w.id DESC LIMIT 50
            """).param("current", currentId)
            .query((row, number) -> readVisible(row, now.toInstant(), true)).list();
    }

    List<VisibleWorry> page(UUID guestId, boolean published, MetadataCursor cursor, int fetchCount, boolean detail) {
        var now = OffsetDateTime.now(clock);
        String publication = published ? " AND w.publish_at <= :now" : " AND w.publish_at > :now";
        String position = cursor == null ? "" : " AND (w.created_at, w.id) < (:cursorTime, :cursorId)";
        String selection = published ? (detail ? WITH_DETAIL : WITH_CONTENT) : METADATA;
        var query = query(selection, guestId, now,
            publication + position + " ORDER BY w.created_at DESC, w.id DESC LIMIT :fetchCount")
            .param("fetchCount", fetchCount);
        if (cursor != null) {
            query.param("cursorTime", cursor.createdAt()).param("cursorId", cursor.worryId());
        }
        return query.query((row, number) -> readVisible(row, now.toInstant(), detail)).list();
    }

    LockedWorry lockPublicAt(UUID guestId, UUID worryId) {
        var locked = lockAccessible(guestId, worryId);
        requirePublic(locked.metadata(), locked.now().toInstant());
        return locked;
    }

    LockedWorry lockAccessible(UUID guestId, UUID worryId) {
        guestWriteLock.requireActive(guestId, ApiError.NOT_FOUND);
        jdbc.sql("SELECT id FROM worries WHERE id = :id AND guest_id = :guestId FOR UPDATE")
            .param("id", worryId).param("guestId", guestId)
            .query(UUID.class).optional().orElseThrow(WorryAccess::notFound);
        var now = OffsetDateTime.now(clock);
        return new LockedWorry(findMetadata(guestId, worryId, now), now);
    }

    private Metadata findMetadata(UUID guestId, UUID worryId, OffsetDateTime now) {
        return query(METADATA, guestId, now, " AND w.id = :id").param("id", worryId)
            .query((row, number) -> readMetadata(row)).optional().orElseThrow(WorryAccess::notFound);
    }

    private JdbcClient.StatementSpec query(String selection, UUID guestId, OffsetDateTime now, String condition) {
        String sql = selection + "FROM worries w\n" + ACCESSIBLE + condition;
        return jdbc.sql(sql).param("guestId", guestId).param("now", now);
    }

    private static Metadata requirePublic(Metadata metadata, Instant now) {
        if (now.isBefore(metadata.publishAt())) {
            throw new BusinessException(ApiError.WORRY_LOCKED);
        }
        return metadata;
    }

    private static BusinessException notFound() {
        return new BusinessException(ApiError.NOT_FOUND);
    }

    private static VisibleWorry readVisible(ResultSet row, Instant now, boolean detail) throws SQLException {
        var metadata = readMetadata(row);
        if (now.isBefore(metadata.publishAt())) {
            return new VisibleWorry(metadata, null);
        }
        return new VisibleWorry(metadata, new EncryptedContent(row.getObject("request_id", UUID.class),
            row.getBytes("input_ciphertext"), row.getBytes("content_ciphertext"),
            row.getBytes("edited_summary_ciphertext"), detail ? row.getBytes("anxiety_after_ciphertext") : null));
    }

    private static Metadata readMetadata(ResultSet row) throws SQLException {
        var viewed = row.getObject("first_viewed_at", OffsetDateTime.class);
        return new Metadata(row.getObject("id", UUID.class),
            row.getObject("created_at", OffsetDateTime.class).toInstant(),
            row.getObject("sealed_at", OffsetDateTime.class).toInstant(),
            row.getObject("publish_at", OffsetDateTime.class).toInstant(),
            viewed == null ? null : viewed.toInstant(),
            row.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    public record Metadata(UUID worryId, Instant createdAt, Instant sealedAt, Instant publishAt,
                           Instant firstViewedAt, Instant expiresAt) {
        public Instant firstAvailableAt() {
            return sealedAt.isAfter(publishAt) ? sealedAt : publishAt;
        }
    }

    record EncryptedContent(UUID requestId, byte[] input, byte[] analysis, byte[] editedSummary, byte[] anxietyAfter) {}
    record VisibleWorry(Metadata metadata, EncryptedContent encrypted) {}
    record MetadataCursor(OffsetDateTime createdAt, UUID worryId) {}
    record LockedWorry(Metadata metadata, OffsetDateTime now) {}
}
