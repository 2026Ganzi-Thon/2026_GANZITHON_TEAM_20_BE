package com.nightvault.backend.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

public enum ApiError {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    GUEST_SESSION_REQUIRED(HttpStatus.UNAUTHORIZED, "게스트로 시작해 주세요."),
    GUEST_SESSION_INVALID(HttpStatus.UNAUTHORIZED, "이 브라우저의 이용 정보를 확인할 수 없어요."),
    CSRF_REJECTED(HttpStatus.FORBIDDEN, "요청을 다시 확인해 주세요."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "이 요청을 처리할 권한이 없어요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "확인할 수 없는 항목이에요."),
    INVALID_PUBLICATION(HttpStatus.UNPROCESSABLE_CONTENT, "공개할 날짜와 시간을 다시 선택해 주세요."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "동일 키가 다른 입력에 사용됐어요."),
    RETRY_NOT_ALLOWED(HttpStatus.CONFLICT, "재시도할 수 있는 상태가 아니에요."),
    WORRY_LOCKED(HttpStatus.CONFLICT, "아직 공개되지 않았어요."),
    OPEN_ACTIVITY_EXISTS(HttpStatus.CONFLICT, "진행 중인 도움이 있어요."),
    INVALID_ACTIVITY_STATE(HttpStatus.CONFLICT, "지금 상태에서는 할 수 없어요."),
    ACTIVITY_ALREADY_ENDED(HttpStatus.CONFLICT, "이미 끝난 활동이에요."),
    ACTION_STEPS_INCOMPLETE(HttpStatus.CONFLICT, "세 단계를 모두 마치면 완료할 수 있어요."),
    REST_NOT_FINISHED(HttpStatus.CONFLICT, "아직 휴식 시간이 남았어요."),
    REST_TIME_OVER(HttpStatus.CONFLICT, "휴식 시간이 끝났어요."),
    ACTIVITY_NOT_COMPLETED(HttpStatus.CONFLICT, "완료한 활동만 평가할 수 있어요."),
    TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;

    ApiError(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public static ApiError unauthenticated(String token) {
        return token == null ? GUEST_SESSION_REQUIRED : GUEST_SESSION_INVALID;
    }

    public ResponseEntity<Body> response() {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body());
    }

    public void write(HttpServletResponse response, JsonMapper json) throws IOException {
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), body());
    }

    private Body body() {
        return new Body(name(), message);
    }

    public record Body(String code, String message) {}
}
