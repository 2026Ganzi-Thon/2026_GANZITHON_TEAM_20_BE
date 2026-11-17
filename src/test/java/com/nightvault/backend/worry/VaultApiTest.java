package com.nightvault.backend.worry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import com.nightvault.backend.support.ApiTestSupport;
import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import com.nightvault.backend.support.WorryFixtures.WorrySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 금고 목록·상세·열람·공개 시각 변경 (S-07·S-08, M-01·M-02, LOCK-01). */
class VaultApiTest extends ApiTestSupport {

    @Test
    void 목록은_잠김과_공개를_나누고_삭제_중_만료_다른_게스트를_뺀다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry published = fixtures.publicWorry(guest);
        TestWorry locked = fixtures.lockedWorry(guest);
        fixtures.deletingWorry(guest);
        fixtures.expiredWorry(guest);
        fixtures.publicWorry(fixtures.guest());

        JsonNode publicItems = client.get("/api/v1/worries?visibility=PUBLIC").body().get("items");
        JsonNode lockedItems = client.get("/api/v1/worries?visibility=LOCKED").body().get("items");

        assertThat(publicItems.size()).isEqualTo(1);
        assertThat(publicItems.get(0).get("worryId").asString()).isEqualTo(published.id().toString());
        assertThat(lockedItems.size()).isEqualTo(1);
        assertThat(lockedItems.get(0).get("worryId").asString()).isEqualTo(locked.id().toString());
        // 잠긴 항목에는 내용이 없다.
        assertThat(lockedItems.get(0).has("summary")).isFalse();
        assertThat(lockedItems.get(0).has("anxietyBefore")).isFalse();
    }

    @Test
    void 목록_limit_경계값과_커서() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        fixtures.publicWorry(guest);
        fixtures.publicWorry(guest);

        assertThat(client.get("/api/v1/worries?visibility=PUBLIC&limit=0").status()).isEqualTo(400);
        assertThat(client.get("/api/v1/worries?visibility=PUBLIC&limit=101").status()).isEqualTo(400);
        assertThat(client.get("/api/v1/worries?visibility=PUBLIC&limit=100").status()).isEqualTo(200);
        assertThat(client.get("/api/v1/worries?visibility=PUBLIC&cursor=broken").status()).isEqualTo(400);

        Response first = client.get("/api/v1/worries?visibility=PUBLIC&limit=1");
        String cursor = first.body().get("nextCursor").asString();
        Response second = client.get("/api/v1/worries?visibility=PUBLIC&limit=1&cursor=" + cursor);
        assertThat(second.body().get("items").size()).isEqualTo(1);
        assertThat(second.body().get("nextCursor").isNull()).isTrue();
        assertThat(second.body().get("items").get(0).get("worryId"))
            .isNotEqualTo(first.body().get("items").get(0).get("worryId"));
        // 다른 영역의 커서는 쓸 수 없다.
        assertThat(client.get("/api/v1/worries?visibility=LOCKED&cursor=" + cursor).status()).isEqualTo(400);
    }

    @Test
    void 잠긴_걱정_상세는_메타데이터만_준다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry locked = fixtures.lockedWorry(guest);

        Response detail = client.get("/api/v1/worries/" + locked.id());

        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("visibility").asString()).isEqualTo("LOCKED");
        assertThat(detail.body().has("text")).isFalse();
        assertThat(detail.body().has("summary")).isFalse();
        assertThat(detail.body().has("analysis")).isFalse();
    }

    @Test
    void 공개_시각에_도달하면_서버_시각_기준으로_공개된다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry locked = lockedAtClock(guest);

        clock.advance(Duration.ofDays(1).minusSeconds(1));
        assertThat(client.get("/api/v1/worries/" + locked.id()).body().get("visibility").asString()).isEqualTo("LOCKED");
        clock.advance(Duration.ofSeconds(1));
        assertThat(client.get("/api/v1/worries/" + locked.id()).body().get("visibility").asString()).isEqualTo("PUBLIC");
    }

    @Test
    void 상세는_다른_게스트_삭제_중_만료면_404다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);

        for (TestWorry hidden : new TestWorry[] {
            fixtures.publicWorry(fixtures.guest()), fixtures.deletingWorry(guest), fixtures.expiredWorry(guest)}) {
            Response response = client.get("/api/v1/worries/" + hidden.id());
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.code()).isEqualTo("NOT_FOUND");
        }
        assertThat(client.get("/api/v1/worries/not-a-uuid").status()).isEqualTo(400);
    }

    @Test
    void 만료_직전까지_보이고_만료_시각부터_404다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        Instant created = clock.instant().minus(Duration.ofDays(2));
        TestWorry worry = fixtures.worry(guest, WorrySpec.of(created, created.plus(Duration.ofDays(1))));

        clock.advance(Duration.ofDays(28).minusSeconds(1));
        assertThat(client.get("/api/v1/worries/" + worry.id()).status()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(1));
        assertThat(client.get("/api/v1/worries/" + worry.id()).status()).isEqualTo(404);
    }

    @Test
    void 최초_열람은_한_번만_저장하고_목록_조회는_열람이_아니다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry worry = fixtures.publicWorry(guest);
        client.get("/api/v1/worries?visibility=PUBLIC");
        client.get("/api/v1/worries/" + worry.id());
        assertThat(firstViewedAt(worry)).isNull();

        Response first = client.post("/api/v1/worries/" + worry.id() + "/view");
        clock.advance(Duration.ofMinutes(1));
        Response again = client.post("/api/v1/worries/" + worry.id() + "/view");

        assertThat(first.status()).isEqualTo(200);
        assertThat(again.body().get("firstViewedAt")).isEqualTo(first.body().get("firstViewedAt"));
        assertThat(firstViewedAt(worry).toInstant()).isEqualTo(clock.instant().minus(Duration.ofMinutes(1)));
    }

    @Test
    void 잠긴_걱정은_열람을_기록하지_않고_409다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry locked = fixtures.lockedWorry(guest);

        Response response = client.post("/api/v1/worries/" + locked.id() + "/view");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("WORRY_LOCKED");
        assertThat(firstViewedAt(locked)).isNull();
        assertThat(client.post("/api/v1/worries/" + fixtures.publicWorry(fixtures.guest()).id() + "/view").status())
            .isEqualTo(404);
    }

    @Test
    void 공개_시각_변경은_미래이고_만료_전인_값만_받는다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry locked = lockedAtClock(guest);
        Instant now = clock.instant();
        Instant expiresAt = now.minus(Duration.ofHours(1)).plus(Duration.ofDays(30));

        assertThat(publication(client, locked, now).status()).isEqualTo(422);
        assertThat(publication(client, locked, expiresAt).status()).isEqualTo(422);
        Response changed = publication(client, locked, expiresAt.minusSeconds(1));
        assertThat(changed.status()).isEqualTo(200);
        assertThat(Instant.parse(changed.body().get("publishAt").asString())).isEqualTo(expiresAt.minusSeconds(1));
        assertThat(publication(client, locked, now.plusSeconds(1)).status()).isEqualTo(200);
    }

    @Test
    void 공개된_걱정의_시각은_바꿀_수_없고_다른_게스트는_404다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        Instant later = clock.instant().plus(Duration.ofDays(1));

        Response published = publication(client, fixtures.publicWorry(guest), later);
        assertThat(published.status()).isEqualTo(409);
        assertThat(published.code()).isEqualTo("ALREADY_PUBLIC");
        assertThat(publication(client, fixtures.lockedWorry(fixtures.guest()), later).status()).isEqualTo(404);
        assertThat(publication(client, fixtures.deletingWorry(guest), later).status()).isEqualTo(404);
    }

    /** 테스트 시계 기준으로 한 시간 전에 쓰고 하루 뒤 공개되는 걱정. 만료는 작성 + 30일. */
    private TestWorry lockedAtClock(TestGuest guest) {
        Instant now = clock.instant();
        return fixtures.worry(guest, WorrySpec.of(now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(1))));
    }

    private Response publication(Client client, TestWorry worry, Instant publishAt) {
        return client.patch("/api/v1/worries/" + worry.id() + "/publication", "{\"publishAt\":\"" + publishAt + "\"}");
    }

    private OffsetDateTime firstViewedAt(TestWorry worry) {
        return jdbc.sql("SELECT first_viewed_at FROM worries WHERE id = :id").param("id", worry.id())
            .query(OffsetDateTime.class).list().get(0);
    }
}
