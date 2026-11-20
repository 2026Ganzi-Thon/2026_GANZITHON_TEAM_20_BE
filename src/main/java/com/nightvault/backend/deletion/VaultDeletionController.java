package com.nightvault.backend.deletion;

import java.util.Map;
import java.util.UUID;

import com.nightvault.backend.api.ApiUuid;
import com.nightvault.backend.guest.GuestSessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class VaultDeletionController {
    private final VaultDeletion deletion;
    private final CsrfTokenRepository csrfTokens;
    private final boolean secure;

    VaultDeletionController(VaultDeletion deletion, CsrfTokenRepository csrfTokens,
                            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        this.deletion = deletion;
        this.csrfTokens = csrfTokens;
        this.secure = secure;
    }

    @DeleteMapping("/api/v1/worries/{worryId}")
    ResponseEntity<?> deleteWorry(@AuthenticationPrincipal UUID guestId, @PathVariable("worryId") String worryId) {
        if (deletion.deleteWorry(guestId, ApiUuid.parse(worryId))) {
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("status", "DELETING"));
    }

    @DeleteMapping("/api/v1/vault")
    ResponseEntity<Void> deleteVault(@AuthenticationPrincipal UUID guestId, HttpServletRequest request,
                                     HttpServletResponse response) {
        deletion.deleteVault(guestId);
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.SET_COOKIE, GuestSessionCookie.clear(secure).toString()).build();
    }
}
