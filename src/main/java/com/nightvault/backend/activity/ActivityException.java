package com.nightvault.backend.activity;

import com.nightvault.backend.api.ApiError;

final class ActivityException extends RuntimeException {
    private final ApiError error;

    ActivityException(ApiError error) {
        super(error.name());
        this.error = error;
    }

    ApiError error() {
        return error;
    }
}
