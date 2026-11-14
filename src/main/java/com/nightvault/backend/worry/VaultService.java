package com.nightvault.backend.worry;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.nightvault.backend.analysis.AnalysisContent;
import com.nightvault.backend.analysis.AnalysisPayloads;
import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.ApiUuid;
import com.nightvault.backend.api.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;

@Service
class VaultService {
    private final JdbcClient jdbc;
    private final WorryAccess access;
    private final AnalysisPayloads payloads;

    VaultService(JdbcClient jdbc, WorryAccess access, AnalysisPayloads payloads) {
        this.jdbc = jdbc;
        this.access = access;
        this.payloads = payloads;
    }

    WorryPage list(UUID guestId, Visibility visibility, String cursor, int limit) {
        var rows = access.page(guestId, visibility == Visibility.PUBLIC, decodeCursor(cursor, visibility), checkedLimit(limit) + 1);
        var page = rows.subList(0, Math.min(rows.size(), limit));
        var items = page.stream().map(this::listItem).toList();
        String nextCursor = rows.size() > limit ? encodeCursor(visibility, page.getLast().metadata()) : null;
        return new WorryPage(visibility, items, nextCursor);
    }

    WorryResponse detail(UUID guestId, UUID worryId) {
        var row = access.find(guestId, worryId);
        var meta = row.metadata();
        if (row.encrypted() == null) {
            return locked(meta);
        }
        var content = decrypt(row);
        return new PublicDetail(meta.worryId(), Visibility.PUBLIC, meta.createdAt(), meta.publishAt(),
            content.summary(), content.input().anxiety(), meta.firstViewedAt(), content.input().text(),
            content.input().memo(), content.anxietyAfter(), content.analysis());
    }

    @Transactional
    public LockedDetail changePublication(UUID guestId, UUID worryId, OffsetDateTime publishAt) {
        var locked = access.lockAccessible(guestId, worryId);
        var meta = locked.metadata();
        if (!locked.now().toInstant().isBefore(meta.publishAt())) {
            throw new BusinessException(ApiError.ALREADY_PUBLIC);
        }
        if (!publishAt.isAfter(locked.now()) || !publishAt.toInstant().isBefore(meta.expiresAt())) {
            throw new BusinessException(ApiError.INVALID_PUBLICATION);
        }
        var stored = jdbc.sql("UPDATE worries SET publish_at = :publishAt WHERE id = :id RETURNING publish_at")
            .param("publishAt", publishAt).param("id", worryId).query(OffsetDateTime.class).single();
        return new LockedDetail(worryId, Visibility.LOCKED, meta.createdAt(), stored.toInstant());
    }

    static int checkedLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        return limit;
    }

    @Transactional
    public ViewResult markViewed(UUID guestId, UUID worryId) {
        var locked = access.lockPublicAt(guestId, worryId);
        if (locked.metadata().firstViewedAt() != null) {
            return new ViewResult(locked.metadata().firstViewedAt());
        }
        var viewed = jdbc.sql("""
            UPDATE worries SET first_viewed_at = :now WHERE id = :id RETURNING first_viewed_at
            """).param("now", locked.now()).param("id", worryId).query(OffsetDateTime.class).single();
        return new ViewResult(viewed.toInstant());
    }

    private WorryResponse listItem(WorryAccess.VisibleWorry row) {
        var meta = row.metadata();
        if (row.encrypted() == null) {
            return locked(meta);
        }
        return new PublicItem(meta.worryId(), Visibility.PUBLIC, meta.createdAt(), meta.publishAt(),
            displaySummary(row), readInput(row).anxiety(), meta.firstViewedAt());
    }

    private static LockedDetail locked(WorryAccess.Metadata meta) {
        return new LockedDetail(meta.worryId(), Visibility.LOCKED, meta.createdAt(), meta.publishAt());
    }

    AnalysisPayloads.StoredInput readInput(WorryAccess.VisibleWorry row) {
        var content = row.encrypted();
        try {
            return payloads.readStoredInput(content.requestId(), content.input());
        } catch (IllegalStateException | IllegalArgumentException | JacksonException exception) {
            throw new BusinessException(ApiError.TEMPORARILY_UNAVAILABLE);
        }
    }

    String displaySummary(WorryAccess.VisibleWorry row) {
        var content = row.encrypted();
        try {
            if (content.editedSummary() != null) {
                return payloads.decryptEditedSummary(row.metadata().worryId(), content.editedSummary());
            }
            return payloads.decryptContent(content.requestId(), content.analysis()).summary();
        } catch (IllegalStateException | IllegalArgumentException | JacksonException exception) {
            throw new BusinessException(ApiError.TEMPORARILY_UNAVAILABLE);
        }
    }

    DecryptedContent decrypt(WorryAccess.VisibleWorry row) {
        var encrypted = row.encrypted();
        var worryId = row.metadata().worryId();
        if (encrypted.analysis() == null) {
            throw new BusinessException(ApiError.TEMPORARILY_UNAVAILABLE);
        }
        try {
            var input = payloads.readStoredInput(encrypted.requestId(), encrypted.input());
            var analysis = payloads.decryptContent(encrypted.requestId(), encrypted.analysis());
            var summary = encrypted.editedSummary() == null
                ? analysis.summary() : payloads.decryptEditedSummary(worryId, encrypted.editedSummary());
            Integer anxiety = encrypted.anxietyAfter() == null
                ? null : payloads.decryptAnxietyAfter(worryId, encrypted.anxietyAfter());
            return new DecryptedContent(input, analysis, summary, anxiety);
        } catch (IllegalStateException | IllegalArgumentException | JacksonException exception) {
            throw new BusinessException(ApiError.TEMPORARILY_UNAVAILABLE);
        }
    }

    static String encodeCursor(Visibility visibility, WorryAccess.Metadata meta) {
        String raw = visibility + "|" + meta.createdAt().atOffset(ZoneOffset.UTC) + "|" + meta.worryId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    static WorryAccess.MetadataCursor decodeCursor(String cursor, Visibility visibility) {
        if (cursor == null) {
            return null;
        }
        if (cursor.length() > 128) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        try {
            var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII).split("\\|", -1);
            if (parts.length == 3 && parts[0].equals(visibility.name())) {
                var time = OffsetDateTime.parse(parts[1]);
                if (time.getYear() >= 1 && time.getYear() <= 9999) {
                    return new WorryAccess.MetadataCursor(time, ApiUuid.parse(parts[2]));
                }
            }
        } catch (IllegalArgumentException | DateTimeException ignored) {
            // Return the same validation error for an invalid cursor.
        }
        throw new BusinessException(ApiError.INVALID_INPUT);
    }

    enum Visibility { LOCKED, PUBLIC }

    interface WorryResponse {}

    record WorryPage(Visibility visibility, List<WorryResponse> items, String nextCursor) {}
    record LockedDetail(UUID worryId, Visibility visibility, Instant createdAt, Instant publishAt)
        implements WorryResponse {}
    record PublicItem(UUID worryId, Visibility visibility, Instant createdAt, Instant publishAt,
                      String summary, int anxietyBefore, Instant firstViewedAt) implements WorryResponse {}
    record PublicDetail(UUID worryId, Visibility visibility, Instant createdAt, Instant publishAt,
                        String summary, int anxietyBefore, Instant firstViewedAt, String text, String memo,
                        Integer anxietyAfter, AnalysisContent analysis) implements WorryResponse {}
    record ViewResult(Instant firstViewedAt) {}
    record DecryptedContent(AnalysisPayloads.StoredInput input, AnalysisContent analysis,
                                    String summary, Integer anxietyAfter) {}
}
