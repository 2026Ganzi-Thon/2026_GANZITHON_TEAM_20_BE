package com.nightvault.backend.analysis;

import java.net.URI;
import java.util.UUID;

import com.nightvault.backend.api.ApiUuid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class AnalysisController {
    private static final String REQUESTS = "/api/v1/worry-requests";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ANALYSIS_CONTEXT = "X-Analysis-Context";

    private final AnalysisRequests requests;

    AnalysisController(AnalysisRequests requests) {
        this.requests = requests;
    }

    @PostMapping(path = REQUESTS, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AnalysisStatus> submit(
        @AuthenticationPrincipal UUID guestId,
        @RequestHeader(IDEMPOTENCY_KEY) String key,
        @RequestHeader(ANALYSIS_CONTEXT) String context,
        @RequestBody JsonNode body
    ) {
        return response(requests.submit(guestId, ApiUuid.parse(key), ApiUuid.parse(context), WorryInput.parse(body)));
    }

    @GetMapping(REQUESTS + "/{requestId}")
    ResponseEntity<AnalysisStatus> get(
        @AuthenticationPrincipal UUID guestId,
        @PathVariable("requestId") String requestId,
        @RequestHeader(name = ANALYSIS_CONTEXT, required = false) String context
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(requests.get(guestId, ApiUuid.parse(requestId), context == null ? null : ApiUuid.parse(context)));
    }

    @PostMapping(path = REQUESTS + "/{requestId}/retry", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AnalysisStatus> retry(
        @AuthenticationPrincipal UUID guestId,
        @PathVariable("requestId") String requestId,
        @RequestHeader(IDEMPOTENCY_KEY) String key,
        @RequestHeader(ANALYSIS_CONTEXT) String context,
        @RequestBody JsonNode body
    ) {
        return response(requests.retry(guestId, ApiUuid.parse(requestId), ApiUuid.parse(key),
            ApiUuid.parse(context), WorryInput.parse(body)));
    }

    @GetMapping("/api/v1/worry-publication/default")
    ResponseEntity<AnalysisRequests.DefaultPublication> defaultPublication(
        @AuthenticationPrincipal UUID guestId, @RequestParam("timeZone") String timeZone
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(requests.defaultPublication(guestId, timeZone));
    }

    private static ResponseEntity<AnalysisStatus> response(AnalysisRequests.Submission submission) {
        var builder = submission.created()
            ? ResponseEntity.accepted().location(URI.create(REQUESTS + "/" + submission.status().requestId()))
            : ResponseEntity.ok();
        return builder.cacheControl(CacheControl.noStore()).body(submission.status());
    }
}
