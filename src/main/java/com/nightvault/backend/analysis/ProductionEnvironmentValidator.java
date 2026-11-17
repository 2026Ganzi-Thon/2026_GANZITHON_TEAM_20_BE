package com.nightvault.backend.analysis;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Validate deployment inputs before datasource auto-configuration obscures missing settings. */
public class ProductionEnvironmentValidator implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.matchesProfiles("prod")) {
            return;
        }
        if (environment.matchesProfiles("local")) {
            throw new IllegalStateException("The prod and local profiles cannot be used together.");
        }
        String url = required(environment, "spring.datasource.url", "NIGHTVAULT_DB_URL");
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("NIGHTVAULT_DB_URL must be a PostgreSQL JDBC URL in the prod profile.");
        }
        required(environment, "spring.datasource.username", "NIGHTVAULT_DB_USER");
        required(environment, "spring.datasource.password", "NIGHTVAULT_DB_PASSWORD");
        if (environment.getProperty("nightvault.demo.enabled", Boolean.class, false)
            && environment.getProperty("NIGHTVAULT_DEMO_KEY", "").isBlank()) {
            throw new IllegalStateException("NIGHTVAULT_DEMO_KEY is required when demo is enabled in the prod profile.");
        }
    }

    private static String required(ConfigurableEnvironment environment, String property, String setting) {
        String value = environment.getProperty(property, "");
        if (value.isBlank()) {
            throw new IllegalStateException(setting + " is required in the prod profile.");
        }
        return value;
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
