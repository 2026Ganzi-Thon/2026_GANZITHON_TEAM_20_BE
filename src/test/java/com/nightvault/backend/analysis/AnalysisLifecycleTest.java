package com.nightvault.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import com.nightvault.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AnalysisLifecycleTest extends IntegrationTest {
    @Autowired
    AnalysisRequests requests;

    @Autowired
    AnalysisJobStore jobs;

    @Test
    void guidanceClearsInputAndExpiresWithinTheOriginalContext() {
        var guest = fixtures.guest();
        var context = UUID.randomUUID();
        var requestId = submit(guest.id(), context);
        var job = jobs.claim(12).orElseThrow();
        jobs.finish(job, new AnalysisJobStore.Result(AnalysisJobStore.Outcome.SUPPORT_GUIDANCE, null));

        var guidance = requests.get(guest.id(), requestId, context);
        assertThat(guidance.status()).isEqualTo("SUPPORT_GUIDANCE");
        assertThat(guidance.guidanceCode()).isEqualTo("S16");
        assertThat(guidance.worryId()).isNull();
        assertThat(worriesFor(requestId)).isZero();
        assertThat(flag(requestId, "input_ciphertext IS NULL AND context_hash IS NOT NULL")).isTrue();
        assertHidden(guest.id(), requestId, null);
        assertHidden(guest.id(), requestId, UUID.randomUUID());
        assertHidden(fixtures.guest().id(), requestId, context);

        clock.advance(Duration.ofMinutes(15));
        assertHidden(guest.id(), requestId, context);
        jobs.finalizeStaleRequests();
        jobs.finalizeStaleRequests();
        assertThat(flag(requestId, """
            state = 'CANCELLED' AND input_ciphertext IS NULL
            AND context_hash IS NULL AND guidance_until IS NULL AND submission_fingerprint IS NULL
            """)).isTrue();
    }

    @Test
    void assessmentFailureClearsInputWithoutCreatingWorry() {
        var guest = fixtures.guest();
        var context = UUID.randomUUID();
        var requestId = submit(guest.id(), context);
        jobs.finish(jobs.claim(12).orElseThrow(), new AnalysisJobStore.Result(AnalysisJobStore.Outcome.FAILURE, null));

        var status = requests.get(guest.id(), requestId, context);
        assertThat(status.status()).isEqualTo("FAILED");
        assertThat(status.worryId()).isNull();
        assertThat(worriesFor(requestId)).isZero();
        assertThat(flag(requestId, "input_ciphertext IS NULL AND context_hash IS NULL")).isTrue();
    }

    @Test
    void expiredInFlightRequestIsClearedAndLateResultCannotSeal() {
        var guest = fixtures.guest();
        var context = UUID.randomUUID();
        var requestId = submit(guest.id(), context);
        var job = jobs.claim(12).orElseThrow();
        clock.advance(Duration.ofDays(30));

        jobs.finalizeStaleRequests();
        jobs.finish(job, new AnalysisJobStore.Result(AnalysisJobStore.Outcome.SUCCESS, null));

        assertThat(worriesFor(requestId)).isZero();
        assertThat(flag(requestId, """
            state = 'CANCELLED' AND input_ciphertext IS NULL AND context_hash IS NULL
            AND attempt_id IS NULL AND lease_until IS NULL
            """)).isTrue();
        assertHidden(guest.id(), requestId, context);
    }

    private UUID submit(UUID guestId, UUID context) {
        return requests.submit(guestId, UUID.randomUUID(), context,
            new WorryInput("합성 테스트 입력", 3, null, clock.instant().plus(Duration.ofHours(1))))
            .status().requestId();
    }

    private int worriesFor(UUID requestId) {
        return jdbc.sql("SELECT count(*) FROM worries WHERE request_id = :id")
            .param("id", requestId).query(Integer.class).single();
    }

    private boolean flag(UUID requestId, String expression) {
        return jdbc.sql("SELECT " + expression + " FROM analysis_requests WHERE id = :id")
            .param("id", requestId).query(Boolean.class).single();
    }

    private void assertHidden(UUID guestId, UUID requestId, UUID context) {
        assertThatThrownBy(() -> requests.get(guestId, requestId, context))
            .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.error()).isEqualTo(ApiError.NOT_FOUND));
    }
}
