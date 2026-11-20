package com.nightvault.backend.api;

import java.util.UUID;

public final class ApiUuid {
    private ApiUuid() {}

    public static UUID parse(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.toString().equalsIgnoreCase(value)) {
                return parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // Both invalid and non-canonical UUIDs use the same public error.
        }
        throw new BusinessException(ApiError.INVALID_INPUT);
    }
}
