package com.nightvault.backend.analysis;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import tools.jackson.databind.JsonNode;

record WorryInput(String text, int anxiety, String memo, Instant publishAt) {
    private static final Pattern BLANK = Pattern.compile("\\p{IsWhite_Space}*");
    private static final Pattern DATE_TIME = Pattern.compile(
        "\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:[Zz]|[+-]\\d{2}:\\d{2})");

    static WorryInput parse(JsonNode body) {
        if (!body.isObject() || body.size() != (body.has("memo") ? 4 : 3)
            || !body.has("text") || !body.has("anxiety") || !body.has("publishAt")) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        try {
            String text = requireText(body.get("text"), 500);
            if (BLANK.matcher(text).matches() || !body.get("anxiety").isNumber()) {
                throw new BusinessException(ApiError.INVALID_INPUT);
            }
            int anxiety = body.get("anxiety").decimalValue().intValueExact();
            if (anxiety < 1 || anxiety > 5) {
                throw new BusinessException(ApiError.INVALID_INPUT);
            }
            String memo = body.has("memo") ? requireText(body.get("memo"), 100) : null;
            String publishAt = requireText(body.get("publishAt"), 40);
            if (!DATE_TIME.matcher(publishAt).matches()) {
                throw new BusinessException(ApiError.INVALID_INPUT);
            }
            return new WorryInput(text, anxiety, memo != null && BLANK.matcher(memo).matches() ? null : memo,
                OffsetDateTime.parse(publishAt).toInstant().truncatedTo(ChronoUnit.MICROS));
        } catch (ArithmeticException | DateTimeParseException exception) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
    }

    static void validatePublication(Instant publishAt, Instant now, Instant expiresAt) {
        if (!publishAt.isAfter(now) || !publishAt.isBefore(expiresAt)) {
            throw new BusinessException(ApiError.INVALID_PUBLICATION);
        }
    }

    private static String requireText(JsonNode value, int limit) {
        if (!value.isString()) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        String text = value.asString();
        if (text.codePointCount(0, text.length()) > limit ||
            text.codePoints().anyMatch(point -> point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE)) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        return text;
    }
}
