package com.nightvault.backend.analysis;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.nightvault.backend.worry.HelpType;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
class LocalAnalysisWorker {
    private static final Logger LOG = LoggerFactory.getLogger(LocalAnalysisWorker.class);
    private static final int MOCK_TIMEOUT_SECONDS = 3;

    private final AnalysisJobStore jobs;
    private final AnalysisPayloads payloads;
    private final PythonAnalysisClient python;
    private final int timeoutSeconds;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    LocalAnalysisWorker(AnalysisJobStore jobs, AnalysisPayloads payloads, PythonAnalysisClient python,
                        @Value("${NIGHTVAULT_AI_TIMEOUT_SECONDS:3}") int httpTimeoutSeconds) {
        if (httpTimeoutSeconds < 1 || httpTimeoutSeconds > 120) {
            throw new IllegalArgumentException("NIGHTVAULT_AI_TIMEOUT_SECONDS must be between 1 and 120.");
        }
        this.jobs = jobs;
        this.payloads = payloads;
        this.python = python;
        this.timeoutSeconds = python.configured() ? httpTimeoutSeconds : MOCK_TIMEOUT_SECONDS;
    }

    @Scheduled(fixedDelay = 500)
    void processNext() {
        if (!payloads.configured()) {
            return;
        }
        try {
            jobs.finalizeStaleRequests();
            var claimed = jobs.claim(timeoutSeconds);
            if (claimed.isEmpty()) {
                return;
            }

            var job = claimed.get();
            var future = executor.submit(() -> analyze(job));
            AnalysisJobStore.Result result;
            try {
                result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                result = new AnalysisJobStore.Result(AnalysisJobStore.Outcome.RETRYABLE_FAILURE, null);
            } catch (ExecutionException exception) {
                LOG.warn("Local analysis failed for request {}", job.requestId());
                result = new AnalysisJobStore.Result(AnalysisJobStore.Outcome.FAILURE, null);
            } catch (InterruptedException exception) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                return;
            }
            jobs.finish(job, result);
        } catch (RuntimeException exception) {
            // Leave a claimed job recoverable after its lease. The exception may contain request data.
            LOG.warn("Local analysis queue operation failed ({})", exception.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    private AnalysisJobStore.Result analyze(AnalysisJobStore.Job job) throws InterruptedException {
        var input = payloads.decryptInput(job.requestId(), job.encryptedInput());
        return python.configured() ? python.analyze(job.requestId(), job.attemptId(), input) : mock(input);
    }

    private static AnalysisJobStore.Result mock(WorryInput input) throws InterruptedException {
        return switch (input.text()) {
            case "[mock:failure]" -> new AnalysisJobStore.Result(AnalysisJobStore.Outcome.FAILURE, null);
            case "[mock:guidance]" -> new AnalysisJobStore.Result(AnalysisJobStore.Outcome.SUPPORT_GUIDANCE, null);
            case "[mock:timeout]" -> {
                Thread.sleep(Duration.ofSeconds(MOCK_TIMEOUT_SECONDS + 1L));
                yield new AnalysisJobStore.Result(AnalysisJobStore.Outcome.RETRYABLE_FAILURE, null);
            }
            default -> new AnalysisJobStore.Result(AnalysisJobStore.Outcome.SUCCESS, new AnalysisContent(
                "로컬 검증용 요약입니다.",
                new AnalysisContent.Recommendation(HelpType.ACTION, "작은 행동부터 연습할 수 있어요."),
                List.of("지금 할 수 있는 한 가지를 골라요.", "할 수 있는 만큼 시작해요.", "해 본 일을 짧게 적어요."),
                "잠시 어깨에 힘을 빼고 천천히 숨을 쉬어요.",
                "마음에 걸리는 일이 있다는 것만으로도 지칠 수 있어요."
            ));
        };
    }
}
