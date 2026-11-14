package com.nightvault.backend.api;

public final class BusinessException extends RuntimeException {
    private final ApiError error;

    public BusinessException(ApiError error) {
        super(error.name());
        this.error = error;
    }

    public ApiError error() {
        return error;
    }
}
