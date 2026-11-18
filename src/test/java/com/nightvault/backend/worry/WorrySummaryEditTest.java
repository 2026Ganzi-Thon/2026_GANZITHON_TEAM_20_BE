package com.nightvault.backend.worry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.nightvault.backend.support.ApiTestSupport;
import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import org.junit.jupiter.api.Test;

/** AI 정리 수정 (M-04). 수정 여부 저장, 수정본 우선, AI 원본 비노출. */
class WorrySummaryEditTest extends ApiTestSupport {
    private static final String AI_SUMMARY = "테스트용 요약입니다.";

    @Test
    void 수정_전에는_AI_요약을_보여주고_수정_여부는_false다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);

        Response detail = client.get("/api/v1/worries/" + worry.id());

        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("summary").asString()).isEqualTo(AI_SUMMARY);
        assertThat(detail.body().get("summaryEdited").asBoolean()).isFalse();
        assertThat(detail.body().get("analysis").has("summary")).isFalse();
        assertThat(detail.body().get("analysis").get("actionSteps").size()).isEqualTo(3);
    }

    @Test
    void 수정하면_수정_여부와_시각을_저장하고_모든_화면에_수정본을_쓴다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        byte[] aiBefore = aiContent(worry);

        Response saved = client.put(summaryPath(worry), "{\"summary\":\"발표 첫 문장이 걱정됐다.\"}");

        assertThat(saved.status()).isEqualTo(204);
        assertThat(editedAt(worry)).isEqualTo(clock.instant().atOffset(ZoneOffset.UTC));
        assertThat(aiContent(worry)).isEqualTo(aiBefore);

        Response detail = client.get("/api/v1/worries/" + worry.id());
        assertThat(detail.body().get("summary").asString()).isEqualTo("발표 첫 문장이 걱정됐다.");
        assertThat(detail.body().get("summaryEdited").asBoolean()).isTrue();
        assertThat(detail.body().get("analysis").has("summary")).isFalse();

        Response record = client.get("/api/v1/records/" + worry.id());
        assertThat(record.body().get("summary").asString()).isEqualTo("발표 첫 문장이 걱정됐다.");
        assertThat(record.body().get("summaryEdited").asBoolean()).isTrue();

        var recordItem = client.get("/api/v1/records").body().get("items").get(0);
        assertThat(recordItem.get("summary").asString()).isEqualTo("발표 첫 문장이 걱정됐다.");
        assertThat(recordItem.get("summaryEdited").asBoolean()).isTrue();

        var vaultItem = client.get("/api/v1/worries?visibility=PUBLIC").body().get("items").get(0);
        assertThat(vaultItem.get("summary").asString()).isEqualTo("발표 첫 문장이 걱정됐다.");
    }

    @Test
    void 다시_수정하면_수정본과_시각을_덮어쓴다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        client.put(summaryPath(worry), "{\"summary\":\"처음 수정\"}");
        clock.advance(Duration.ofMinutes(5));

        client.put(summaryPath(worry), "{\"summary\":\"두 번째 수정\"}");

        assertThat(editedAt(worry)).isEqualTo(clock.instant().atOffset(ZoneOffset.UTC));
        assertThat(client.get("/api/v1/worries/" + worry.id()).body().get("summary").asString()).isEqualTo("두 번째 수정");
    }

    @Test
    void 글자_수_경계값() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);

        assertThat(client.put(summaryPath(worry), body("가")).status()).isEqualTo(204);
        assertThat(client.put(summaryPath(worry), body("가".repeat(500))).status()).isEqualTo(204);
        // 이모지는 코드 포인트 하나로 센다.
        assertThat(client.put(summaryPath(worry), body("😀".repeat(500))).status()).isEqualTo(204);

        Response tooLong = client.put(summaryPath(worry), body("가".repeat(501)));
        Response blank = client.put(summaryPath(worry), body(" \\n\\t "));
        Response empty = client.put(summaryPath(worry), body(""));

        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.code()).isEqualTo("INVALID_INPUT");
        assertThat(blank.status()).isEqualTo(400);
        assertThat(empty.status()).isEqualTo(400);
        // 실패한 요청은 마지막 성공 값을 바꾸지 않는다.
        assertThat(client.get("/api/v1/worries/" + worry.id()).body().get("summary").asString()).isEqualTo("😀".repeat(500));
    }

    @Test
    void 잠긴_걱정은_409_다른_게스트_삭제_만료는_404다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry locked = fixtures.lockedWorry(guest);
        TestWorry deleting = fixtures.deletingWorry(guest);
        TestWorry expired = fixtures.expiredWorry(guest);
        TestWorry others = fixtures.publicWorry(fixtures.guest());

        Response lockedResponse = client.put(summaryPath(locked), body("수정"));
        assertThat(lockedResponse.status()).isEqualTo(409);
        assertThat(lockedResponse.code()).isEqualTo("WORRY_LOCKED");
        for (TestWorry hidden : new TestWorry[] {deleting, expired, others}) {
            Response response = client.put(summaryPath(hidden), body("수정"));
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.code()).isEqualTo("NOT_FOUND");
            assertThat(editedAt(hidden)).isNull();
        }
    }

    private static String summaryPath(TestWorry worry) {
        return "/api/v1/worries/" + worry.id() + "/summary";
    }

    private static String body(String summary) {
        return "{\"summary\":\"" + summary + "\"}";
    }

    private OffsetDateTime editedAt(TestWorry worry) {
        return jdbc.sql("SELECT summary_edited_at FROM worries WHERE id = :id").param("id", worry.id())
            .query(OffsetDateTime.class).list().get(0);
    }

    private byte[] aiContent(TestWorry worry) {
        return jdbc.sql("SELECT content_ciphertext FROM ai_contents WHERE worry_id = :id").param("id", worry.id())
            .query(byte[].class).single();
    }
}
