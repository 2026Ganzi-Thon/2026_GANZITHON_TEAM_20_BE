package com.nightvault.backend.guest;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

final class GuestSessionCookie {
    private static final String NAME = "NIGHTVAULT_SESSION";

    private GuestSessionCookie() {}

    static String read(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String token = null;
        for (var cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                if (token != null) {
                    return "";
                }
                token = cookie.getValue() == null ? "" : cookie.getValue();
            }
        }
        return token;
    }

    static ResponseCookie create(String token, boolean secure) {
        return ResponseCookie.from(NAME, token)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/")
            .maxAge(GuestSessionService.SESSION_LIFETIME)
            .build();
    }
}
