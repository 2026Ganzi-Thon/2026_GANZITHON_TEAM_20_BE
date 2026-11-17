package com.nightvault.backend.worry;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.nightvault.backend.analysis.AnalysisPayloads;
import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorryEdits {
    private final JdbcClient jdbc;
    private final WorryAccess access;
    private final AnalysisPayloads payloads;
    private final Clock clock;

    WorryEdits(JdbcClient jdbc, WorryAccess access, AnalysisPayloads payloads, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.payloads = payloads;
        this.clock = clock;
    }

    @Transactional
    public void updateSummary(UUID guestId, UUID worryId, String summary) {
        if (summary == null || summary.codePointCount(0, summary.length()) > 500
            || summary.matches("\\p{IsWhite_Space}*")
            || summary.codePoints().anyMatch(point -> point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE)) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        access.lockPublic(guestId, worryId);
        // AI 원본(ai_contents)은 그대로 두고 수정본과 수정 시각만 덮어쓴다. 되돌리기는 없다.
        jdbc.sql("UPDATE worries SET edited_summary_ciphertext = :ciphertext, summary_edited_at = :now WHERE id = :id")
            .param("ciphertext", payloads.encryptEditedSummary(worryId, summary))
            .param("now", OffsetDateTime.now(clock))
            .param("id", worryId).update();
    }

    @Transactional
    public void updateAnxiety(UUID guestId, UUID worryId, int anxiety) {
        access.lockPublic(guestId, worryId);
        jdbc.sql("UPDATE worries SET anxiety_after_ciphertext = :ciphertext WHERE id = :id")
            .param("ciphertext", payloads.encryptAnxietyAfter(worryId, anxiety))
            .param("id", worryId).update();
    }
}
