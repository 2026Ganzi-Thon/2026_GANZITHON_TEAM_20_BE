package com.nightvault.backend.guest;

import com.nightvault.backend.demo.DemoAccess;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시연 게스트 세션 발급(DEMO-01). 세션 쿠키 발급 규칙을 게스트 패키지 안에서 재사용하려고 이 패키지에 둔다.
 * 시연 키가 틀리면 다른 시연 API와 같이 404다.
 */
@RestController
class DemoSessionController {
    private final GuestSessionService sessions;
    private final CsrfTokenRepository csrfTokens;
    private final DemoAccess access;
    private final boolean secureCookies;

    DemoSessionController(
        GuestSessionService sessions,
        CsrfTokenRepository csrfTokens,
        DemoAccess access,
        @Value("${server.servlet.session.cookie.secure:true}") boolean secureCookies
    ) {
        this.sessions = sessions;
        this.csrfTokens = csrfTokens;
        this.access = access;
        this.secureCookies = secureCookies;
    }

    @PostMapping("/api/v1/demo/session")
    ResponseEntity<GuestSessionController.SessionResponse> start(
        @RequestHeader(name = DemoAccess.HEADER, required = false) String key,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        access.requireKey(key);
        var session = sessions.startDemo(GuestSessionCookie.read(request));
        if (session.issued()) {
            if (request.getSession(false) != null) {
                request.changeSessionId();
            }
            csrfTokens.saveToken(null, request, response);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.SET_COOKIE, GuestSessionCookie.create(session.token(), secureCookies).toString())
            .body(new GuestSessionController.SessionResponse(true, session.expiresAt()));
    }
}
