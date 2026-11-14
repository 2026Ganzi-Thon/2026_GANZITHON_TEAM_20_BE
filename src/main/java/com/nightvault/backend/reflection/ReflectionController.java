package com.nightvault.backend.reflection;

import java.time.ZoneId;
import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reflections")
class ReflectionController {
    private final Reflections reflections;

    ReflectionController(Reflections reflections) {
        this.reflections = reflections;
    }

    @GetMapping("/candidate")
    ResponseEntity<Reflections.Candidate> candidate(@AuthenticationPrincipal UUID guest,
        @RequestParam UUID currentWorryId, @RequestParam String timeZone) {
        var result = reflections.candidate(guest, currentWorryId, zone(timeZone));
        return result == null ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
            : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/skip-today")
    ResponseEntity<Void> skip(@AuthenticationPrincipal UUID guest, @RequestParam String timeZone) {
        reflections.skip(guest, zone(timeZone));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/{worryId}")
    ResponseEntity<Reflections.Response> read(@AuthenticationPrincipal UUID guest, @PathVariable UUID worryId) {
        var result = reflections.read(guest, worryId);
        return result == null ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
            : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PutMapping("/{worryId}")
    ResponseEntity<Void> answer(@AuthenticationPrincipal UUID guest, @PathVariable UUID worryId,
        @RequestParam UUID currentWorryId, @RequestParam String timeZone, @RequestBody Response input) {
        if (input.answer() == null) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        reflections.answer(guest, worryId, currentWorryId, zone(timeZone), input.answer());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static ZoneId zone(String timeZone) {
        if (!ZoneId.getAvailableZoneIds().contains(timeZone)) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        return ZoneId.of(timeZone);
    }

    record Response(Reflections.Answer answer) {}
}
