package com.nightvault.backend.guest;

import com.nightvault.backend.api.ApiError;

import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GuestSessionController {
    private final GuestSessionService sessions;
    private final CsrfTokenRepository csrfTokens;
    private final boolean secureCookies;

    GuestSessionController(
        GuestSessionService sessions,
        CsrfTokenRepository csrfTokens,
        @Value("${server.servlet.session.cookie.secure:true}") boolean secureCookies
    ) {
        this.sessions = sessions;
        this.csrfTokens = csrfTokens;
        this.secureCookies = secureCookies;
    }

    @GetMapping("/api/v1/csrf")
    ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new CsrfResponse(token.getToken(), token.getHeaderName()));
    }

    @PostMapping(path = "/api/v1/guest-sessions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> start(
        @RequestBody Map<String, Object> input,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        Object mode = input.get("mode");
        if (input.size() != 1 || !("START".equals(mode) || "RESTART".equals(mode))) {
            return ApiError.INVALID_INPUT.response();
        }

        String cookie = GuestSessionCookie.read(request);
        var result = sessions.start(cookie, "RESTART".equals(mode));
        if (result.isEmpty()) {
            return ApiError.GUEST_SESSION_INVALID.response();
        }
        var session = result.get();
        if (session.issued()) {
            if (request.getSession(false) != null) {
                request.changeSessionId();
            }
            csrfTokens.saveToken(null, request, response);
        }
        return sessionResponse(session);
    }

    @GetMapping("/api/v1/guest-sessions/current")
    ResponseEntity<?> current(HttpServletRequest request) {
        String cookie = GuestSessionCookie.read(request);
        var session = sessions.refresh(cookie);
        return session.isPresent() ? sessionResponse(session.get()) : ApiError.unauthenticated(cookie).response();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError.Body> invalidRequest() {
        return ApiError.INVALID_INPUT.response();
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiError.Body> databaseUnavailable() {
        return ApiError.TEMPORARILY_UNAVAILABLE.response();
    }

    private ResponseEntity<SessionResponse> sessionResponse(GuestSessionService.SessionCookie session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.SET_COOKIE, GuestSessionCookie.create(session.token(), secureCookies).toString())
            .body(new SessionResponse(true, session.expiresAt()));
    }

    record CsrfResponse(String token, String headerName) {}
    record SessionResponse(boolean authenticated, Instant expiresAt) {}
}
