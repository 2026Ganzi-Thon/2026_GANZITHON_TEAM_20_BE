package com.nightvault.backend.support;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 게스트·세션·분석 요청·걱정 행을 SQL로 직접 넣는 테스트 픽스처.
 *
 * <p>분석 워커를 거치지 않고 시각을 마음대로 정해 "공개됨/잠김/삭제 중/만료" 상태를 바로 만든다.
 * 암호문은 BE1 {@code AnalysisPayloads}와 같은 형식(AES-GCM, nonce 12바이트 + 암호문, AAD {@code input:/content:{requestId}})으로
 * 테스트 고정 키를 써서 만든다. 형식이 맞는지는 {@code analysis.FixtureCiphertextCompatibilityTest}가 확인한다.</p>
 *
 * <p>상태 판정 기준(BE2 설계 §3 공통 조건): 삭제 중 = {@code deletion_started_at IS NOT NULL},
 * 만료 = {@code expires_at <= now}, 잠김 = {@code publish_at > now}, 나머지 = 공개됨.
 * 프리셋은 모두 봉인(작성 + 1분)이 공개보다 앞서므로 {@code GREATEST(sealed_at, publish_at) = publish_at}이다.</p>
 */
@TestComponent
public class WorryFixtures {
    public static final String SESSION_COOKIE = "NIGHTVAULT_SESSION";
    public static final Duration RETENTION = Duration.ofDays(30);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SecretKeySpec key;

    WorryFixtures(JdbcClient jdbc, JsonMapper json, @Value("${NIGHTVAULT_DATA_KEY}") String dataKey) {
        this.jdbc = jdbc;
        this.json = json;
        this.key = new SecretKeySpec(Base64.getDecoder().decode(dataKey), "AES");
    }

    // ---- 게스트 ----

    /** ACTIVE 게스트 + 기본 설정 + 30일짜리 세션. {@code sessionToken}을 {@link #SESSION_COOKIE} 쿠키로 보내면 인증된다. */
    public TestGuest guest() {
        UUID guestId = jdbc.sql("INSERT INTO guests DEFAULT VALUES RETURNING id").query(UUID.class).single();
        jdbc.sql("INSERT INTO guest_settings (guest_id) VALUES (:guestId)").param("guestId", guestId).update();

        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        jdbc.sql("INSERT INTO guest_sessions (guest_id, token_hash, expires_at) VALUES (:guestId, :hash, :expiresAt)")
            .param("guestId", guestId)
            .param("hash", sha256(token))
            .param("expiresAt", utc(now().plus(RETENTION)))
            .update();
        return new TestGuest(guestId, token);
    }

    // ---- 상태별 걱정 ----

    /** 이틀 전에 쓰고 하루 전에 공개된 걱정. 만료까지 28일 남음. */
    public TestWorry publicWorry(TestGuest guest) {
        Instant created = now().minus(Duration.ofDays(2));
        return worry(guest, WorrySpec.of(created, now().minus(Duration.ofDays(1))));
    }

    /** 한 시간 전에 쓰고 내일 공개되는 걱정. */
    public TestWorry lockedWorry(TestGuest guest) {
        Instant created = now().minus(Duration.ofHours(1));
        return worry(guest, WorrySpec.of(created, now().plus(Duration.ofDays(1))));
    }

    /** 공개됐지만 1분 전에 삭제가 시작된 걱정. */
    public TestWorry deletingWorry(TestGuest guest) {
        Instant created = now().minus(Duration.ofDays(2));
        return worry(guest, WorrySpec.of(created, now().minus(Duration.ofDays(1)))
            .withDeletionStartedAt(now().minus(Duration.ofMinutes(1))));
    }

    /** 31일 전에 써서 하루 전에 만료된 걱정(아직 정리 작업 전). */
    public TestWorry expiredWorry(TestGuest guest) {
        Instant created = now().minus(Duration.ofDays(31));
        return worry(guest, WorrySpec.of(created, created.plus(Duration.ofDays(1))));
    }

    // ---- 일반형 ----

    /**
     * 분석 요청(SUCCEEDED) + 걱정 + AI 콘텐츠를 한 번에 넣는다. BE1 {@code AnalysisJobStore.seal}과 같은 모양이다.
     * {@code worries_publication_check}(created &lt; publish &lt; expires)를 어기면 DB가 거부한다.
     */
    public TestWorry worry(TestGuest guest, WorrySpec spec) {
        UUID requestId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO analysis_requests
                    (id, guest_id, submission_key, state, accepted_at, expires_at, publish_at, next_attempt_at)
                VALUES (:id, :guestId, :key, 'SUCCEEDED', :createdAt, :expiresAt, :publishAt, :createdAt)
                """)
            .param("id", requestId)
            .param("guestId", guest.id())
            .param("key", UUID.randomUUID())
            .param("createdAt", utc(spec.createdAt()))
            .param("expiresAt", utc(spec.expiresAt()))
            .param("publishAt", utc(spec.publishAt()))
            .update();

        UUID worryId = jdbc.sql("""
                INSERT INTO worries (guest_id, request_id, created_at, sealed_at, publish_at, first_viewed_at,
                                     expires_at, deletion_started_at, input_ciphertext)
                VALUES (:guestId, :requestId, :createdAt, :sealedAt, :publishAt, :firstViewedAt,
                        :expiresAt, :deletionStartedAt, :input)
                RETURNING id
                """)
            .param("guestId", guest.id())
            .param("requestId", requestId)
            .param("createdAt", utc(spec.createdAt()))
            .param("sealedAt", utc(spec.sealedAt()))
            .param("publishAt", utc(spec.publishAt()))
            .param("firstViewedAt", utc(spec.firstViewedAt()))
            .param("expiresAt", utc(spec.expiresAt()))
            .param("deletionStartedAt", utc(spec.deletionStartedAt()))
            .param("input", encrypt("input:" + requestId, inputJson(spec)))
            .query(UUID.class).single();

        jdbc.sql("INSERT INTO ai_contents (worry_id, content_ciphertext) VALUES (:id, :content)")
            .param("id", worryId)
            .param("content", encrypt("content:" + requestId, json.writeValueAsBytes(DEFAULT_CONTENT)))
            .update();
        return new TestWorry(worryId, requestId, guest.id());
    }

    /** 테스트마다 같은 기준으로 쓰도록 마이크로초 단위(PostgreSQL 정밀도)로 자른 현재 시각. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    // ---- 내부 ----

    private byte[] inputJson(WorrySpec spec) {
        // BE1 WorryInput(text, anxiety, memo, publishAt)과 같은 필드·순서
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", spec.text());
        input.put("anxiety", spec.anxiety());
        input.put("memo", spec.memo());
        input.put("publishAt", spec.publishAt());
        return json.writeValueAsBytes(input);
    }

    private byte[] encrypt(String aad, byte[] plaintext) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static final Map<String, Object> DEFAULT_CONTENT = Map.of(
        "summary", "테스트용 요약입니다.",
        "recommendation", Map.of("type", "ACTION", "reason", "작은 행동부터 연습할 수 있어요."),
        "actionSteps", List.of("한 가지를 골라요.", "할 수 있는 만큼 시작해요.", "해 본 일을 적어요."),
        "restGuide", "잠시 숨을 천천히 쉬어요.",
        "empathyMessage", "마음에 걸리는 일이 있다는 것만으로도 지칠 수 있어요."
    );

    // ---- 값 타입 ----

    public record TestGuest(UUID id, String sessionToken) {}

    public record TestWorry(UUID id, UUID requestId, UUID guestId) {}

    /** 걱정 한 건의 시각·입력값. {@link #of}는 봉인 = 작성 + 1분, 만료 = 작성 + 30일로 채운다. */
    public record WorrySpec(
        Instant createdAt, Instant sealedAt, Instant publishAt, Instant expiresAt,
        Instant firstViewedAt, Instant deletionStartedAt,
        String text, int anxiety, String memo
    ) {
        public static WorrySpec of(Instant createdAt, Instant publishAt) {
            return new WorrySpec(createdAt, createdAt.plus(Duration.ofMinutes(1)), publishAt, createdAt.plus(RETENTION),
                null, null, "내일 발표가 걱정돼요.", 3, null);
        }

        public WorrySpec withDeletionStartedAt(Instant value) {
            return new WorrySpec(createdAt, sealedAt, publishAt, expiresAt, firstViewedAt, value, text, anxiety, memo);
        }

        public WorrySpec withFirstViewedAt(Instant value) {
            return new WorrySpec(createdAt, sealedAt, publishAt, expiresAt, value, deletionStartedAt, text, anxiety, memo);
        }

        public WorrySpec withInput(String newText, int newAnxiety, String newMemo) {
            return new WorrySpec(createdAt, sealedAt, publishAt, expiresAt, firstViewedAt, deletionStartedAt,
                newText, newAnxiety, newMemo);
        }
    }
}
