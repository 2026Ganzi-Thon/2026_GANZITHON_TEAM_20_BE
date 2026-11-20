package com.nightvault.backend.demo;

import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시연 패널 API(DEMO-01~03). 보안 설정에서 permitAll이므로 세션이 없으면 {@code guestId}가 null이고,
 * 권한 판정은 {@link DemoAccess}가 404로 처리한다.
 */
@RestController
@RequestMapping("/api/v1/demo")
class DemoController {
    private final DemoService demo;
    private final DemoAccess access;
    private final JsonMapper json;

    DemoController(DemoService demo, DemoAccess access, JsonMapper json) {
        this.demo = demo;
        this.access = access;
        this.json = json;
    }

    @GetMapping("/status")
    ResponseEntity<DemoService.Status> status(
        @RequestHeader(name = DemoAccess.HEADER, required = false) String key,
        @AuthenticationPrincipal UUID guestId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(demo.status(key, guestId));
    }

    @PostMapping("/time-machine")
    ResponseEntity<DemoService.TimeMachineResult> timeMachine(
        @RequestHeader(name = DemoAccess.HEADER, required = false) String key,
        @AuthenticationPrincipal UUID guestId,
        @RequestBody(required = false) String body
    ) {
        // 본문 오류(400)보다 키 확인(404)을 먼저 해서 시연 API의 존재를 드러내지 않는다.
        access.requireKey(key);
        TimeMachineRequest input;
        try {
            input = body == null ? null : json.readValue(body, TimeMachineRequest.class);
        } catch (JacksonException exception) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        if (input == null || input.preset() == null) {
            throw new BusinessException(ApiError.INVALID_INPUT);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(demo.timeMachine(key, guestId, input.preset(), input.worryId()));
    }

    @PostMapping("/presets")
    ResponseEntity<DemoService.Status> presets(
        @RequestHeader(name = DemoAccess.HEADER, required = false) String key,
        @AuthenticationPrincipal UUID guestId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(demo.presets(key, guestId));
    }

    @PostMapping("/reset")
    ResponseEntity<DemoService.Status> reset(
        @RequestHeader(name = DemoAccess.HEADER, required = false) String key,
        @AuthenticationPrincipal UUID guestId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(demo.reset(key, guestId));
    }

    record TimeMachineRequest(DemoService.TimeMachinePreset preset, UUID worryId) {}
}
