package com.nightvault.backend.analysis;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
record AnalysisStatus(
    UUID requestId, Instant acceptedAt, String status, UUID worryId, Instant sealedAt,
    Instant publishAt, String failureCode, String guidanceCode
) {
    static AnalysisStatus pending(UUID id, Instant acceptedAt) {
        return new AnalysisStatus(id, acceptedAt, "PENDING", null, null, null, null, null);
    }

    static AnalysisStatus succeeded(UUID id, Instant acceptedAt, UUID worryId, Instant sealedAt, Instant publishAt) {
        return new AnalysisStatus(id, acceptedAt, "SUCCEEDED", worryId, sealedAt, publishAt, null, null);
    }

    static AnalysisStatus failed(UUID id, Instant acceptedAt) {
        return new AnalysisStatus(id, acceptedAt, "FAILED", null, null, null, "ANALYSIS_FAILED", null);
    }

    static AnalysisStatus guidance(UUID id, Instant acceptedAt) {
        return new AnalysisStatus(id, acceptedAt, "SUPPORT_GUIDANCE", null, null, null, null, "S16");
    }
}
