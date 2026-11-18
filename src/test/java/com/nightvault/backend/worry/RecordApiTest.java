package com.nightvault.backend.worry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import com.nightvault.backend.support.ApiTestSupport;
import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import com.nightvault.backend.support.WorryFixtures.WorrySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 기록 목록·상세 (R-01~03)와 현재 불안도 (M-03). */
class RecordApiTest extends ApiTestSupport {

    @Test
    void 목록은_공개된_내_기록만_작성_최신순으로_보여준다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry older = published(guest, Duration.ofDays(3));
        TestWorry newer = published(guest, Duration.ofDays(2));
        fixtures.lockedWorry(guest);
        fixtures.deletingWorry(guest);
        fixtures.expiredWorry(guest);
        fixtures.publicWorry(fixtures.guest());

        JsonNode items = client.get("/api/v1/records").body().get("items");

        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).get("worryId").asString()).isEqualTo(newer.id().toString());
        assertThat(items.get(1).get("worryId").asString()).isEqualTo(older.id().toString());
    }

    @Test
    void 불안도와_도움을_고르지_않았으면_null로_구분한다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        fixtures.publicWorry(guest);

        JsonNode item = client.get("/api/v1/records").body().get("items").get(0);

        assertThat(item.get("anxietyBefore").asInt()).isEqualTo(3);
        assertThat(item.get("anxietyAfter").isNull()).isTrue();
        assertThat(item.get("anxietyDelta").isNull()).isTrue();
        assertThat(item.get("latestHelpType").isNull()).isTrue();
        assertThat(item.get("summaryEdited").asBoolean()).isFalse();
    }

    @Test
    void 현재_불안도를_저장하면_변화량을_계산하고_최신_도움을_보여준다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry worry = fixtures.publicWorry(guest);
        client.put(anxietyPath(worry), "{\"anxietyAfter\":5}");
        client.put(anxietyPath(worry), "{\"anxietyAfter\":1}");
        client.post("/api/v1/worries/" + worry.id() + "/activities", "{\"helpType\":\"ACTION\"}");
        clock.advance(Duration.ofSeconds(1));
        client.post("/api/v1/worries/" + worry.id() + "/activities", "{\"helpType\":\"EMPATHY\",\"replaceOpen\":true}");

        JsonNode item = client.get("/api/v1/records").body().get("items").get(0);

        assertThat(item.get("anxietyAfter").asInt()).isEqualTo(1);
        assertThat(item.get("anxietyDelta").asInt()).isEqualTo(-2);
        assertThat(item.get("latestHelpType").asString()).isEqualTo("EMPATHY");
        assertThat(client.get("/api/v1/records/" + worry.id()).body().get("anxietyAfter").asInt()).isEqualTo(1);
    }

    @Test
    void 현재_불안도_경계값() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry worry = fixtures.publicWorry(guest);

        assertThat(client.put(anxietyPath(worry), "{\"anxietyAfter\":1}").status()).isEqualTo(204);
        assertThat(client.put(anxietyPath(worry), "{\"anxietyAfter\":5}").status()).isEqualTo(204);
        for (String body : new String[] {"{\"anxietyAfter\":0}", "{\"anxietyAfter\":6}", "{\"anxietyAfter\":\"3\"}",
            "{\"anxietyAfter\":2.5}", "{\"anxietyAfter\":null}", "{}", "{\"anxietyAfter\":3,\"extra\":1}"}) {
            Response response = client.put(anxietyPath(worry), body);
            assertThat(response.status()).as(body).isEqualTo(400);
        }
        assertThat(client.get("/api/v1/records/" + worry.id()).body().get("anxietyAfter").asInt()).isEqualTo(5);
    }

    @Test
    void 현재_불안도는_잠긴_걱정에_409_다른_게스트_삭제_만료에_404다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);

        Response locked = client.put(anxietyPath(fixtures.lockedWorry(guest)), "{\"anxietyAfter\":2}");
        assertThat(locked.status()).isEqualTo(409);
        assertThat(locked.code()).isEqualTo("WORRY_LOCKED");
        for (TestWorry hidden : new TestWorry[] {
            fixtures.publicWorry(fixtures.guest()), fixtures.deletingWorry(guest), fixtures.expiredWorry(guest)}) {
            assertThat(client.put(anxietyPath(hidden), "{\"anxietyAfter\":2}").status()).isEqualTo(404);
        }
    }

    @Test
    void 상세는_매번_소유권_공개_삭제_만료를_검사한다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry worry = fixtures.publicWorry(guest);
        assertThat(client.get("/api/v1/records/" + worry.id()).status()).isEqualTo(200);

        for (TestWorry hidden : new TestWorry[] {fixtures.publicWorry(fixtures.guest()), fixtures.lockedWorry(guest),
            fixtures.deletingWorry(guest), fixtures.expiredWorry(guest)}) {
            Response response = client.get("/api/v1/records/" + hidden.id());
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.code()).isEqualTo("NOT_FOUND");
        }
        client.delete("/api/v1/worries/" + worry.id());
        assertThat(client.get("/api/v1/records/" + worry.id()).status()).isEqualTo(404);
    }

    @Test
    void 기록이_없으면_빈_목록이다() {
        Response response = client(fixtures.guest()).get("/api/v1/records");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("items").size()).isZero();
        assertThat(response.body().get("nextCursor").isNull()).isTrue();
    }

    @Test
    void 목록_limit_경계값과_커서() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        published(guest, Duration.ofDays(3));
        published(guest, Duration.ofDays(2));

        assertThat(client.get("/api/v1/records?limit=0").status()).isEqualTo(400);
        assertThat(client.get("/api/v1/records?limit=101").status()).isEqualTo(400);
        assertThat(client.get("/api/v1/records?limit=100").status()).isEqualTo(200);
        assertThat(client.get("/api/v1/records?cursor=broken").status()).isEqualTo(400);

        Response first = client.get("/api/v1/records?limit=1");
        Response second = client.get("/api/v1/records?limit=1&cursor=" + first.body().get("nextCursor").asString());
        assertThat(second.body().get("items").size()).isEqualTo(1);
        assertThat(second.body().get("nextCursor").isNull()).isTrue();
    }

    private TestWorry published(TestGuest guest, Duration ago) {
        Instant created = clock.instant().minus(ago);
        return fixtures.worry(guest, WorrySpec.of(created, created.plus(Duration.ofHours(10))));
    }

    private static String anxietyPath(TestWorry worry) {
        return "/api/v1/worries/" + worry.id() + "/anxiety";
    }
}
