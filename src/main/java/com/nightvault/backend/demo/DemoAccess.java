package com.nightvault.backend.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시연 API 권한 검사(DEMO-01).
 *
 * <p>{@code nightvault.demo.enabled=true}이고 환경변수 {@code NIGHTVAULT_DEMO_KEY}가 설정된 환경에서,
 * 요청 헤더 {@value #HEADER}가 그 키와 같을 때만 시연 API를 연다. 꺼짐·키 없음·키 틀림·일반 게스트는
 * 모두 {@code 404 NOT_FOUND}로 응답해 시연 기능의 존재를 드러내지 않는다.</p>
 */
@Component
public class DemoAccess {
    public static final String HEADER = "X-Demo-Key";

    private final JdbcClient jdbc;
    private final byte[] keyDigest;

    DemoAccess(JdbcClient jdbc,
               @Value("${nightvault.demo.enabled:false}") boolean enabled,
               @Value("${NIGHTVAULT_DEMO_KEY:}") String key) {
        this.jdbc = jdbc;
        this.keyDigest = enabled && !key.isBlank() ? digest(key) : null;
    }

    /** 시연 키가 맞지 않으면 404. */
    public void requireKey(String provided) {
        if (keyDigest == null || provided == null || !MessageDigest.isEqual(keyDigest, digest(provided))) {
            throw new BusinessException(ApiError.NOT_FOUND);
        }
    }

    /** 키 확인 + 현재 게스트가 활성 시연 게스트인지 확인(조회용). */
    UUID requireDemoGuest(String provided, UUID guestId) {
        requireKey(provided);
        if (guestId == null || !jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM guests WHERE id = :id AND state = 'ACTIVE' AND kind = 'DEMO')
                """).param("id", guestId).query(Boolean.class).single()) {
            throw new BusinessException(ApiError.NOT_FOUND);
        }
        return guestId;
    }

    /** 키 확인 + 시연 게스트 행 잠금(변경용). 일반 게스트 행은 잠그지도 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    UUID lockDemoGuest(String provided, UUID guestId) {
        requireKey(provided);
        if (guestId == null || jdbc.sql("""
                SELECT id FROM guests WHERE id = :id AND state = 'ACTIVE' AND kind = 'DEMO' FOR UPDATE
                """).param("id", guestId).query(UUID.class).optional().isEmpty()) {
            throw new BusinessException(ApiError.NOT_FOUND);
        }
        return guestId;
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
