package com.nightvault.backend.worry;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorryRecords {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private final WorryAccess access;
    private final VaultService vault;
    private final ObjectProvider<WorryActivityHistory> activityHistory;

    WorryRecords(WorryAccess access, VaultService vault, ObjectProvider<WorryActivityHistory> activityHistory) {
        this.access = access;
        this.vault = vault;
        this.activityHistory = activityHistory;
    }

    VaultService.PublicDetail record(UUID guestId, UUID worryId) {
        var response = vault.detail(guestId, worryId);
        if (response instanceof VaultService.PublicDetail published) {
            return published;
        }
        throw new BusinessException(ApiError.NOT_FOUND);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecordPage records(UUID guestId, String cursor, int limit) {
        var rows = access.page(guestId, true, VaultService.decodeCursor(cursor, VaultService.Visibility.PUBLIC), VaultService.checkedLimit(limit) + 1, true);
        var page = rows.subList(0, Math.min(rows.size(), limit));
        var history = latestActivities(guestId, page);
        var items = page.stream().map(row -> {
            var meta = row.metadata();
            var data = vault.decrypt(row);
            int before = data.input().anxiety();
            Integer after = data.anxietyAfter();
            var latest = history.get(meta.worryId());
            return new RecordItem(meta.worryId(), meta.createdAt(), data.summary(), before, after,
                after == null ? null : after - before, latest == null ? null : latest.type());
        }).toList();
        return new RecordPage(items, rows.size() > limit ? VaultService.encodeCursor(VaultService.Visibility.PUBLIC, page.getLast().metadata()) : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PastExperience pastExperience(UUID guestId, UUID worryId) {
        var current = access.find(guestId, worryId);
        if (current.encrypted() == null) {
            throw new BusinessException(ApiError.NOT_FOUND);
        }
        var words = words(vault.readInput(current).text());
        if (words.size() < 2) {
            return null;
        }
        WorryAccess.VisibleWorry best = null;
        double bestScore = 0.2;
        var candidates = access.pastCandidates(guestId, worryId);
        var history = latestActivities(guestId, candidates);
        for (var row : candidates) {
            var choice = history.get(row.metadata().worryId());
            if (choice == null) {
                continue;
            }
            var candidateWords = words(vault.readInput(row).text());
            long common = candidateWords.stream().filter(words::contains).count();
            double score = (double) common / Math.max(1, words.size() + candidateWords.size() - common);
            if (common >= 2 && score >= 0.2 && (best == null || score > bestScore)) {
                bestScore = score;
                best = row;
            }
        }
        if (best == null) {
            return null;
        }
        var id = best.metadata().worryId();
        var choice = history.get(id);
        return new PastExperience(id, vault.displaySummary(best), choice.type(), choice.helpful());
    }

    private Map<UUID, WorryActivityHistory.Latest> latestActivities(UUID guestId, List<WorryAccess.VisibleWorry> rows) {
        var history = activityHistory.getIfAvailable();
        return history == null ? Map.of() : history.latestFor(guestId,
            rows.stream().map(row -> row.metadata().worryId()).toList());
    }

    private static Set<String> words(String text) {
        return WORD.matcher(text.toLowerCase(Locale.ROOT)).results()
            .map(match -> match.group()).collect(Collectors.toSet());
    }

    record RecordItem(UUID worryId, Instant createdAt, String summary, int anxietyBefore, Integer anxietyAfter,
                      Integer anxietyDelta, HelpType latestHelpType) {}
    record RecordPage(List<RecordItem> items, String nextCursor) {}
    record PastExperience(UUID worryId, String summary, HelpType helpType, boolean helpful) {}
}
