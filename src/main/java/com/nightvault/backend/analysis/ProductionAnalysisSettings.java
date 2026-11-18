package com.nightvault.backend.analysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
class ProductionAnalysisSettings {
    ProductionAnalysisSettings(
        Environment environment,
        AnalysisPayloads payloads,
        PythonAnalysisClient python,
        @Value("${NIGHTVAULT_AI_TOKEN:}") String token,
        @Value("${server.servlet.session.cookie.secure:true}") boolean secureCookies
    ) {
        if (environment.matchesProfiles("local")) {
            throw new IllegalStateException("The prod and local profiles cannot be used together.");
        }
        if (!secureCookies) {
            throw new IllegalStateException("The prod profile requires Secure session cookies.");
        }
        if (!payloads.configured()) {
            throw new IllegalStateException("NIGHTVAULT_DATA_KEY is required in the prod profile.");
        }
        if (!python.configured() || token.isBlank()) {
            throw new IllegalStateException("NIGHTVAULT_AI_URL and NIGHTVAULT_AI_TOKEN are required in the prod profile.");
        }
    }
}
