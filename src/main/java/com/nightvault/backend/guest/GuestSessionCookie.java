package com.nightvault.backend.guest;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

public final class GuestSessionCookie {
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
        return cookie(token, secure).maxAge(GuestSessionService.SESSION_LIFETIME).build();
    }

    public static ResponseCookie clear(boolean secure) {
        return cookie("", secure).maxAge(0).build();
    }

    private static ResponseCookie.ResponseCookieBuilder cookie(String token, boolean secure) {
        return ResponseCookie.from(NAME, token)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/");
    }
}
