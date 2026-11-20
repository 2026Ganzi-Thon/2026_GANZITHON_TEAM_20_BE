package com.nightvault.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class ProductionAnalysisSettingsTest {
    private static final String DATA_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final ApplicationContextRunner analysis = new ApplicationContextRunner()
        .withUserConfiguration(
            AnalysisWorker.class, PythonAnalysisClient.class, ProductionAnalysisSettings.class,
            AnalysisPayloads.class
        )
        .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
        .withBean(AnalysisJobStore.class, () -> mock(AnalysisJobStore.class));

    @Test
    void prodStartsTheWorkerWithAnAuthenticatedPythonEndpoint() {
        production().run(context -> {
            assertThat(context).hasSingleBean(AnalysisWorker.class);
            assertThat(context).hasSingleBean(PythonAnalysisClient.class);
            assertThat(context.getBean(PythonAnalysisClient.class).configured()).isTrue();
        });
    }

    @Test
    void prodRequiresThePythonUrlAndToken() {
        production().withPropertyValues("NIGHTVAULT_AI_URL=").run(context ->
            assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("NIGHTVAULT_AI_URL and NIGHTVAULT_AI_TOKEN are required in the prod profile."));
        production().withPropertyValues("NIGHTVAULT_AI_TOKEN=").run(context ->
            assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("NIGHTVAULT_AI_URL and NIGHTVAULT_AI_TOKEN are required in the prod profile."));
    }

    @Test
    void prodRequiresAnEncryptionKeyAndSecureCookies() {
        production().withPropertyValues("NIGHTVAULT_DATA_KEY=").run(context ->
            assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("NIGHTVAULT_DATA_KEY is required in the prod profile."));
        production().withPropertyValues("server.servlet.session.cookie.secure=false").run(context ->
            assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("The prod profile requires Secure session cookies."));
    }

    @Test
    void prodCannotLoadLocalSettings() {
        production().withInitializer(context -> context.getEnvironment().setActiveProfiles("prod", "local"))
            .run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseMessage("The prod and local profiles cannot be used together."));
    }

    @Test
    void localStillAllowsTheBuiltInMockAndTestHasNoWorker() {
        analysis.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
            .run(context -> {
                assertThat(context).hasSingleBean(AnalysisWorker.class);
                assertThat(context.getBean(PythonAnalysisClient.class).configured()).isFalse();
            });
        analysis.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
            .run(context -> assertThat(context).doesNotHaveBean(AnalysisWorker.class));
    }

    private ApplicationContextRunner production() {
        return analysis.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
            .withPropertyValues(
                "NIGHTVAULT_DATA_KEY=" + DATA_KEY,
                "NIGHTVAULT_AI_URL=http://python.internal:8001/internal/v1/analyze",
                "NIGHTVAULT_AI_TOKEN=synthetic-test-token"
            );
    }
}
