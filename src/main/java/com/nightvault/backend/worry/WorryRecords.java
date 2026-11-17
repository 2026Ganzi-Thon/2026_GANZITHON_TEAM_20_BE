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
    private static final double MIN_SCORE = 0.2;
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
            return new RecordItem(meta.worryId(), meta.createdAt(), data.summary(), data.summaryEdited(), before, after,
                after == null ? null : after - before, latest == null ? null : latest.type());
        }).toList();
        return new RecordPage(items, rows.size() > limit ? VaultService.encodeCursor(VaultService.Visibility.PUBLIC, page.getLast().metadata()) : null);
    }

    /**
     * 관련 과거 경험 (PAST-01·02). 관련성은 표시용 요약(수정본 우선, M-04)의 단어 겹침으로 계산한다.
     * 활동을 고르지 않은 기록도 후보에 넣는다. 그때 helpType은 null, helpful은 false다.
     * 활동 중 하나라도 "도움 됨" 평가가 있으면 helpful은 true, helpType은 그 활동의 유형이다.
     * 없으면 helpType은 가장 최근 활동의 유형이다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PastExperience pastExperience(UUID guestId, UUID worryId) {
        var current = access.find(guestId, worryId);
        if (current.encrypted() == null) {
            throw new BusinessException(ApiError.NOT_FOUND);
        }
        var words = words(vault.displaySummary(current));
        if (words.size() < 2) {
            return null;
        }
        WorryAccess.VisibleWorry best = null;
        String bestSummary = null;
        double bestScore = 0;
        for (var row : access.pastCandidates(guestId, worryId)) {
            var summary = vault.displaySummary(row);
            var candidateWords = words(summary);
            long common = candidateWords.stream().filter(words::contains).count();
            double score = (double) common / Math.max(1, words.size() + candidateWords.size() - common);
            if (common >= 2 && score >= MIN_SCORE && (best == null || score > bestScore)) {
                bestScore = score;
                best = row;
                bestSummary = summary;
            }
        }
        if (best == null) {
            return null;
        }
        var id = best.metadata().worryId();
        var history = activityHistory.getIfAvailable();
        var helpful = history == null ? java.util.Optional.<HelpType>empty() : history.latestHelpful(guestId, id);
        if (helpful.isPresent()) {
            return new PastExperience(id, bestSummary, helpful.get(), true);
        }
        var choice = latestActivities(guestId, List.of(best)).get(id);
        return new PastExperience(id, bestSummary, choice == null ? null : choice.type(), false);
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

    record RecordItem(UUID worryId, Instant createdAt, String summary, boolean summaryEdited, int anxietyBefore, Integer anxietyAfter,
                      Integer anxietyDelta, HelpType latestHelpType) {}
    record RecordPage(List<RecordItem> items, String nextCursor) {}
    record PastExperience(UUID worryId, String summary, HelpType helpType, boolean helpful) {}
}
