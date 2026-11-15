package com.nightvault.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.nightvault.backend.support.IntegrationTest;
import com.nightvault.backend.worry.HelpType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DemoAnalysisWorkerTest extends IntegrationTest {
    private static final String EXAMPLE = "[시연 예시] ";

    @Autowired
    private AnalysisRequests requests;
    @Autowired
    private AnalysisJobStore jobs;
    @Autowired
    private AnalysisPayloads payloads;

    private PythonAnalysisClient python;
    private LocalAnalysisWorker worker;

    @BeforeEach
    void configureWorker() {
        python = mock(PythonAnalysisClient.class);
        when(python.configured()).thenReturn(true);
        worker = new LocalAnalysisWorker(jobs, payloads, python, 12);
        clearInvocations(python);
    }

    @AfterEach
    void stopWorker() {
        worker.stop();
    }

    @Test
    void demoSubmissionUsesExampleAndPreservesInputAndPublication() {
        var guestId = demoGuest();
        var context = UUID.randomUUID();
        var input = input("[mock:timeout]");
        var requestId = requests.submit(guestId, UUID.randomUUID(), context, input).status().requestId();

        worker.processNext();

        var status = requests.get(guestId, requestId, context);
        assertThat(status.status()).isEqualTo("SUCCEEDED");
        assertThat(status.publishAt()).isEqualTo(input.publishAt());
        assertThat(storedInput(status.worryId(), requestId)).isEqualTo(input);
        var content = storedContent(status.worryId(), requestId);
        assertThat(content.summary()).startsWith(EXAMPLE);
        assertThat(content.recommendation().reason()).startsWith(EXAMPLE);
        assertThat(content.actionSteps()).hasSize(3).allSatisfy(step -> assertThat(step).startsWith(EXAMPLE));
        assertThat(content.restGuide()).startsWith(EXAMPLE);
        assertThat(content.empathyMessage()).startsWith(EXAMPLE);
        verifyNoInteractions(python);
    }

    @Test
    void demoPresetFailureCanBeRetriedInTheSameRequestWithoutPython() {
        var guestId = demoGuest();
        var acceptedAt = clock.instant().minus(Duration.ofMinutes(5)).atOffset(ZoneOffset.UTC);
        var requestId = jdbc.sql("""
            INSERT INTO analysis_requests (guest_id, submission_key, state, accepted_at, expires_at, next_attempt_at)
            VALUES (:guest, :key, 'FAILED', :accepted, :expires, :accepted)
            RETURNING id
            """).param("guest", guestId).param("key", UUID.randomUUID())
            .param("accepted", acceptedAt).param("expires", acceptedAt.plusDays(30)).query(UUID.class).single();
        var context = UUID.randomUUID();
        var input = input("발표 재시도용 걱정");
        var retry = requests.retry(guestId, requestId, UUID.randomUUID(), context, input).status();
        assertThat(retry.status()).isEqualTo("PENDING");

        worker.processNext();

        var status = requests.get(guestId, requestId, context);
        assertThat(status.status()).isEqualTo("SUCCEEDED");
        assertThat(status.requestId()).isEqualTo(retry.requestId()).isEqualTo(requestId);
        assertThat(status.acceptedAt()).isEqualTo(acceptedAt.toInstant());
        assertThat(status.publishAt()).isEqualTo(input.publishAt());
        assertThat(storedInput(status.worryId(), requestId)).isEqualTo(input);
        assertThat(storedContent(status.worryId(), requestId).summary()).startsWith(EXAMPLE);
        verifyNoInteractions(python);
    }

    @Test
    void regularGuestKeepsItsPythonResult() throws InterruptedException {
        var guestId = fixtures.guest().id();
        var context = UUID.randomUUID();
        var input = input("일반 사용자 걱정");
        var requestId = requests.submit(guestId, UUID.randomUUID(), context, input).status().requestId();
        var result = new AnalysisContent("Python이 반환한 합성 요약",
            new AnalysisContent.Recommendation(HelpType.ACTION, "한 단계씩 준비하기"),
            List.of("첫 단계", "둘째 단계", "셋째 단계"), "합성 휴식 안내", "합성 공감 문구");
        when(python.analyze(eq(requestId), any(UUID.class), eq(input)))
            .thenReturn(new AnalysisJobStore.Result(AnalysisJobStore.Outcome.SUCCESS, result));

        worker.processNext();

        var status = requests.get(guestId, requestId, context);
        assertThat(status.status()).isEqualTo("SUCCEEDED");
        assertThat(storedContent(status.worryId(), requestId)).isEqualTo(result);
        verify(python).analyze(eq(requestId), any(UUID.class), eq(input));
    }

    @Test
    void regularGuestKeepsItsPythonFailure() throws InterruptedException {
        var guestId = fixtures.guest().id();
        var context = UUID.randomUUID();
        var input = input("일반 사용자 걱정");
        var requestId = requests.submit(guestId, UUID.randomUUID(), context, input).status().requestId();
        when(python.analyze(eq(requestId), any(UUID.class), eq(input)))
            .thenReturn(new AnalysisJobStore.Result(AnalysisJobStore.Outcome.FAILURE, null));

        worker.processNext();

        var status = requests.get(guestId, requestId, context);
        assertThat(status.status()).isEqualTo("FAILED");
        assertThat(status.worryId()).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM worries WHERE request_id = :id").param("id", requestId)
            .query(Long.class).single()).isZero();
        verify(python).analyze(eq(requestId), any(UUID.class), eq(input));
    }

    private UUID demoGuest() {
        var guestId = fixtures.guest().id();
        jdbc.sql("UPDATE guests SET kind = 'DEMO' WHERE id = :id").param("id", guestId).update();
        return guestId;
    }

    private WorryInput input(String text) {
        return new WorryInput(text, 3, "합성 메모", clock.instant().plus(Duration.ofHours(1)));
    }

    private WorryInput storedInput(UUID worryId, UUID requestId) {
        byte[] ciphertext = jdbc.sql("SELECT input_ciphertext FROM worries WHERE id = :id")
            .param("id", worryId).query((row, number) -> row.getBytes("input_ciphertext")).single();
        return payloads.decryptInput(requestId, ciphertext);
    }

    private AnalysisContent storedContent(UUID worryId, UUID requestId) {
        byte[] ciphertext = jdbc.sql("SELECT content_ciphertext FROM ai_contents WHERE worry_id = :id")
            .param("id", worryId).query((row, number) -> row.getBytes("content_ciphertext")).single();
        return payloads.decryptContent(requestId, ciphertext);
    }
}
