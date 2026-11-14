package com.nightvault.backend.analysis;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.nightvault.backend.worry.HelpType;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("local")
class PythonAnalysisClient {
    private static final int MAX_RESPONSE_BYTES = 32 * 1024;
    private final URI endpoint;
    private final String token;
    private final JsonMapper json;
    private final ObjectReader responseReader;
    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(1))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    PythonAnalysisClient(JsonMapper json, @Value("${NIGHTVAULT_AI_URL:}") String url,
                         @Value("${NIGHTVAULT_AI_TOKEN:}") String token) {
        this.json = json;
        this.responseReader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.endpoint = url.isBlank() ? null : parseEndpoint(url);
        this.token = token;
    }

    boolean configured() {
        return endpoint != null;
    }

    AnalysisJobStore.Result analyze(UUID requestId, UUID attemptId, WorryInput input) throws InterruptedException {
        var body = json.createObjectNode()
            .put("requestId", requestId.toString())
            .put("attemptId", attemptId.toString())
            .put("text", input.text())
            .put("anxiety", input.anxiety());
        if (input.memo() != null) {
            body.put("memo", input.memo());
        }
        var request = HttpRequest.newBuilder(endpoint)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        if (!token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }

        try {
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var responseBody = response.body()) {
                if (response.statusCode() == 503) {
                    return result(AnalysisJobStore.Outcome.RETRYABLE_FAILURE);
                }
                if (response.statusCode() != 200
                    || response.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_RESPONSE_BYTES) {
                    return result(AnalysisJobStore.Outcome.FAILURE);
                }
                byte[] bytes = responseBody.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    return result(AnalysisJobStore.Outcome.FAILURE);
                }
                return parseResult(responseReader.readTree(bytes), requestId, attemptId);
            }
        } catch (IOException exception) {
            return result(AnalysisJobStore.Outcome.RETRYABLE_FAILURE);
        } catch (JacksonException | IllegalArgumentException exception) {
            return result(AnalysisJobStore.Outcome.FAILURE);
        }
    }

    @PreDestroy
    void stop() {
        http.shutdownNow();
    }

    private static URI parseEndpoint(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("NIGHTVAULT_AI_URL must be an HTTP or HTTPS endpoint.");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("NIGHTVAULT_AI_URL must be an HTTP or HTTPS endpoint.");
        }
        return uri;
    }

    private static AnalysisJobStore.Result parseResult(JsonNode response, UUID requestId, UUID attemptId) {
        requireObject(response, 4);
        if (!requestId.toString().equalsIgnoreCase(text(response.get("requestId")))
            || !attemptId.toString().equalsIgnoreCase(text(response.get("attemptId")))) {
            return result(AnalysisJobStore.Outcome.FAILURE);
        }
        return switch (text(response.get("outcome"))) {
            case "SUCCESS" -> new AnalysisJobStore.Result(AnalysisJobStore.Outcome.SUCCESS,
                parseContent(response.get("content")));
            case "SUPPORT_GUIDANCE" -> result("S16".equals(text(response.get("guidanceCode")))
                ? AnalysisJobStore.Outcome.SUPPORT_GUIDANCE : AnalysisJobStore.Outcome.FAILURE);
            case "FAILURE" -> switch (text(response.get("failureCode"))) {
                case "PROVIDER_TIMEOUT", "PROVIDER_RATE_LIMITED", "PROVIDER_UNAVAILABLE" ->
                    result(AnalysisJobStore.Outcome.RETRYABLE_FAILURE);
                default -> result(AnalysisJobStore.Outcome.FAILURE);
            };
            default -> result(AnalysisJobStore.Outcome.FAILURE);
        };
    }

    private static AnalysisContent parseContent(JsonNode content) {
        requireObject(content, 5);
        var recommendation = content.get("recommendation");
        requireObject(recommendation, 2);
        var steps = content.get("actionSteps");
        if (steps == null || !steps.isArray() || steps.size() != 3) {
            throw new IllegalArgumentException("Invalid analysis action steps.");
        }
        return new AnalysisContent(text(content.get("summary")),
            new AnalysisContent.Recommendation(HelpType.valueOf(text(recommendation.get("type"))),
                text(recommendation.get("reason"))),
            List.of(text(steps.get(0)), text(steps.get(1)), text(steps.get(2))),
            text(content.get("restGuide")), text(content.get("empathyMessage")));
    }

    private static void requireObject(JsonNode value, int fields) {
        if (value == null || !value.isObject() || value.size() != fields) {
            throw new IllegalArgumentException("Invalid analysis response shape.");
        }
    }

    private static String text(JsonNode value) {
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException("Invalid analysis text field.");
        }
        return value.asString();
    }

    private static AnalysisJobStore.Result result(AnalysisJobStore.Outcome outcome) {
        return new AnalysisJobStore.Result(outcome, null);
    }
}
