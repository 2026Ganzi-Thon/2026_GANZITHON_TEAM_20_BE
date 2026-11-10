package com.nightvault.backend.analysis;

import com.nightvault.backend.api.ApiError;

final class AnalysisException extends RuntimeException {
    private final ApiError error;

    AnalysisException(ApiError error) {
        super(error.name());
        this.error = error;
    }

    ApiError error() {
        return error;
    }
}
