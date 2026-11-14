package com.nightvault.backend.worry;

import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
class WorryRecordController {
    private final WorryRecords records;
    private final WorryEdits edits;

    WorryRecordController(WorryRecords records, WorryEdits edits) {
        this.records = records;
        this.edits = edits;
    }

    @GetMapping("/api/v1/records")
    ResponseEntity<WorryRecords.RecordPage> list(@AuthenticationPrincipal UUID guest,
        @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(records.records(guest, cursor, limit));
    }

    @GetMapping("/api/v1/records/{worryId}")
    ResponseEntity<VaultService.PublicDetail> detail(@AuthenticationPrincipal UUID guest, @PathVariable UUID worryId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(records.record(guest, worryId));
    }

    @GetMapping("/api/v1/worries/{worryId}/past-experience")
    ResponseEntity<WorryRecords.PastExperience> past(@AuthenticationPrincipal UUID guest, @PathVariable UUID worryId) {
        var result = records.pastExperience(guest, worryId);
        return result == null ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
            : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PutMapping("/api/v1/worries/{worryId}/summary")
    ResponseEntity<Void> summary(@AuthenticationPrincipal UUID guest, @PathVariable UUID worryId, @RequestBody JsonNode body) {
        var value = body.get("summary");
        if (!body.isObject() || body.size() != 1 || value == null || !value.isString()) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        edits.updateSummary(guest, worryId, value.asString());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PutMapping("/api/v1/worries/{worryId}/anxiety")
    ResponseEntity<Void> anxiety(@AuthenticationPrincipal UUID guest, @PathVariable UUID worryId, @RequestBody JsonNode body) {
        var value = body.get("anxietyAfter");
        if (!body.isObject() || body.size() != 1 || value == null || !value.isIntegralNumber()
            || !value.canConvertToInt() || value.asInt() < 1 || value.asInt() > 5) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        edits.updateAnxiety(guest, worryId, value.asInt());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
