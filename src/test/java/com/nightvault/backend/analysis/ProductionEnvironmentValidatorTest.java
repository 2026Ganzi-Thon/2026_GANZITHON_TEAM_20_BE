package com.nightvault.backend.analysis;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionEnvironmentValidatorTest {
    private final ProductionEnvironmentValidator validator = new ProductionEnvironmentValidator();

    @Test
    void localAndTestDoNotRequireDeploymentCredentials() {
        for (String profile : new String[] {"local", "test"}) {
            var environment = new MockEnvironment();
            environment.setActiveProfiles(profile);
            assertThatCode(() -> validator.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
        }
    }

    @Test
    void missingDatabaseInputsNameTheRequiredSettingWithoutEchoingValues() {
        String[] properties = {"spring.datasource.url", "spring.datasource.username", "spring.datasource.password"};
        String[] names = {"NIGHTVAULT_DB_URL", "NIGHTVAULT_DB_USER", "NIGHTVAULT_DB_PASSWORD"};
        for (int i = 0; i < properties.length; i++) {
            var environment = production().withProperty(properties[i], " ");
            String message = names[i] + " is required in the prod profile.";
            assertThatThrownBy(() -> validator.postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class).hasMessage(message);
        }
    }

    @Test
    void invalidJdbcUrlDoesNotLeakItsContents() {
        var environment = production().withProperty("spring.datasource.url", "https://synthetic-private-address");
        assertThatThrownBy(() -> validator.postProcessEnvironment(environment, null))
            .hasMessage("NIGHTVAULT_DB_URL must be a PostgreSQL JDBC URL in the prod profile.");
    }

    @Test
    void demoEnabledRequiresItsKey() {
        var environment = production().withProperty("nightvault.demo.enabled", "true");
        assertThatThrownBy(() -> validator.postProcessEnvironment(environment, null))
            .hasMessage("NIGHTVAULT_DEMO_KEY is required when demo is enabled in the prod profile.");
    }

    @Test
    void validDeploymentCanEnableDemoWithAKeyOrLeaveItDisabled() {
        assertThatCode(() -> validator.postProcessEnvironment(production(), null)).doesNotThrowAnyException();
        var demo = production().withProperty("nightvault.demo.enabled", "true")
            .withProperty("NIGHTVAULT_DEMO_KEY", "synthetic-key");
        assertThatCode(() -> validator.postProcessEnvironment(demo, null)).doesNotThrowAnyException();
    }

    @Test
    void mixedProfilesFailBeforeReadingDevelopmentCredentials() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "local");
        assertThatThrownBy(() -> validator.postProcessEnvironment(environment, null))
            .hasMessage("The prod and local profiles cannot be used together.");
    }

    private MockEnvironment production() {
        var environment = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1/test")
            .withProperty("spring.datasource.username", "synthetic-user")
            .withProperty("spring.datasource.password", "synthetic-password");
        environment.setActiveProfiles("prod");
        return environment;
    }
}
