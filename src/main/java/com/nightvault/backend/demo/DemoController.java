package com.nightvault.backend.demo;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시연 패널 API(DEMO-01~03). 보안 설정에서 permitAll이므로 세션이 없으면 {@code guestId}가 null이고,
 * 권한 판정은 {@link DemoAccess}가 404로 처리한다.
 */
@RestController
@RequestMapping("/api/v1/demo")
class DemoController {
    private final DemoService demo;

    DemoController(DemoService demo) {
        this.demo = demo;
    }

    @GetMapping("/status")
    ResponseEntity<DemoService.Status> status(
        @RequestHeader(name = DemoAccess.HEADER, required = false) String key,
        @AuthenticationPrincipal UUID guestId
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(demo.status(key, guestId));
    }
}
