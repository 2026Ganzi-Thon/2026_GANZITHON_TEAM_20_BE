package com.nightvault.backend.worry;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.ApiUuid;
import com.nightvault.backend.api.BusinessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class VaultController {
    private final VaultService vault;
    private final VaultHome home;

    VaultController(VaultService vault, VaultHome home) {
        this.vault = vault;
        this.home = home;
    }

    @GetMapping("/api/v1/vault/summary")
    ResponseEntity<VaultHome.Summary> summary(@AuthenticationPrincipal UUID guestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(home.get(guestId));
    }

    @GetMapping("/api/v1/worries")
    ResponseEntity<VaultService.WorryPage> list(
        @AuthenticationPrincipal UUID guestId,
        @RequestParam("visibility") VaultService.Visibility visibility,
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(vault.list(guestId, visibility, cursor, limit));
    }

    @GetMapping("/api/v1/worries/{worryId}")
    ResponseEntity<VaultService.WorryResponse> detail(
        @AuthenticationPrincipal UUID guestId, @PathVariable("worryId") String worryId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(vault.detail(guestId, ApiUuid.parse(worryId)));
    }

    @PatchMapping("/api/v1/worries/{worryId}/publication")
    ResponseEntity<VaultService.LockedDetail> publication(
        @AuthenticationPrincipal UUID guestId, @PathVariable("worryId") String worryId, @RequestBody JsonNode input
    ) {
        var time = input.get("publishAt");
        if (!input.isObject() || input.size() != 1 || time == null || !time.isString()) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        try {
            var publishAt = OffsetDateTime.parse(time.asString()).truncatedTo(ChronoUnit.MICROS);
            if (publishAt.getYear() < 1 || publishAt.getYear() > 9999) {
                throw new BusinessException(ApiError.INVALID_INPUT);
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(vault.changePublication(guestId, ApiUuid.parse(worryId), publishAt));
        } catch (DateTimeParseException | ArithmeticException exception) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
    }

    @PostMapping("/api/v1/worries/{worryId}/view")
    ResponseEntity<VaultService.ViewResult> markViewed(
        @AuthenticationPrincipal UUID guestId, @PathVariable("worryId") String worryId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(vault.markViewed(guestId, ApiUuid.parse(worryId)));
    }
}
