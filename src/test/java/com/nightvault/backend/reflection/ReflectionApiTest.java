package com.nightvault.backend.reflection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.nightvault.backend.support.ApiTestSupport;
import com.nightvault.backend.support.WorryFixtures;
import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import com.nightvault.backend.support.WorryFixtures.WorrySpec;
import org.junit.jupiter.api.Test;

/** 회고 후보·응답·오늘 건너뛰기 (REVIEW-01~03). */
class ReflectionApiTest extends ApiTestSupport {
    private static final String ZONE = "Asia/Seoul";

    @Test
    void 공개_가능_시각부터_정확히_24시간이면_후보가_되고_1초_전에는_아니다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);
        TestWorry target = viewed(guest, Duration.ofHours(24).minusSeconds(1));

        assertThat(candidate(client, current).status()).isEqualTo(204);

        clock.advance(Duration.ofSeconds(1));
        Response response = candidate(client, current);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("worryId").asString()).isEqualTo(target.id().toString());
    }

    @Test
    void 미열람_현재_답한_기록은_빼고_가장_오래된_공개_가능_시각을_고른다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry newer = viewed(guest, Duration.ofDays(2));
        TestWorry oldest = viewed(guest, Duration.ofDays(4));
        TestWorry notViewed = fixtures.worry(guest, spec(Duration.ofDays(5)));

        assertThat(candidate(client, newer).body().get("worryId").asString()).isEqualTo(oldest.id().toString());
        // 현재 보고 있는 기록은 후보에서 빠진다.
        assertThat(candidate(client, oldest).body().get("worryId").asString()).isEqualTo(newer.id().toString());

        assertThat(answer(client, oldest, newer, "BETTER").status()).isEqualTo(204);
        assertThat(candidate(client, notViewed).body().get("worryId").asString()).isEqualTo(newer.id().toString());
    }

    @Test
    void 삭제_중_만료_다른_게스트의_기록은_후보가_아니다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);
        fixtures.worry(guest, spec(Duration.ofDays(3)).withFirstViewedAt(clock.instant().minus(Duration.ofDays(2)))
            .withDeletionStartedAt(clock.instant()));
        Instant created = WorryFixtures.now().minus(Duration.ofDays(31));
        fixtures.worry(guest, WorrySpec.of(created, created.plus(Duration.ofDays(1))).withFirstViewedAt(created.plus(Duration.ofDays(2))));
        viewed(fixtures.guest(), Duration.ofDays(3));

        assertThat(candidate(client, current).status()).isEqualTo(204);
    }

    @Test
    void 잘못된_시간대는_400이다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);

        Response candidate = client.get("/api/v1/reflections/candidate?currentWorryId=" + current.id() + "&timeZone=Mars/Base");
        Response skip = client.post("/api/v1/reflections/skip-today?timeZone=Mars/Base");

        assertThat(candidate.status()).isEqualTo(400);
        assertThat(skip.status()).isEqualTo(400);
        assertThat(skipRows(guest)).isZero();
    }

    @Test
    void 오늘_건너뛰기는_현지_날짜와_시간대를_저장하고_같은_날에는_후보를_숨긴다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);
        viewed(guest, Duration.ofDays(2));
        viewed(guest, Duration.ofDays(3));

        Response skipped = client.post("/api/v1/reflections/skip-today?timeZone=" + ZONE);

        assertThat(skipped.status()).isEqualTo(204);
        var row = jdbc.sql("SELECT local_date, time_zone, skipped_at FROM reflection_skips WHERE guest_id = :guest")
            .param("guest", guest.id())
            .query((rs, i) -> new Object[] {rs.getObject("local_date", LocalDate.class), rs.getString("time_zone"),
                rs.getObject("skipped_at", OffsetDateTime.class).toInstant()})
            .single();
        assertThat(row[0]).isEqualTo(clock.instant().atZone(ZoneId.of(ZONE)).toLocalDate());
        assertThat(row[1]).isEqualTo(ZONE);
        assertThat(row[2]).isEqualTo(clock.instant());
        // 같은 날에는 다른 회고도 제안하지 않는다.
        assertThat(candidate(client, current).status()).isEqualTo(204);
    }

    @Test
    void 같은_날_다시_건너뛰어도_처음_값을_유지한다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        client.post("/api/v1/reflections/skip-today?timeZone=" + ZONE);
        Instant first = clock.instant();
        clock.advance(Duration.ofMinutes(1));

        assertThat(client.post("/api/v1/reflections/skip-today?timeZone=" + ZONE).status()).isEqualTo(204);

        assertThat(skipRows(guest)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT skipped_at FROM reflection_skips WHERE guest_id = :guest").param("guest", guest.id())
            .query(OffsetDateTime.class).single().toInstant()).isEqualTo(first);
    }

    @Test
    void 다음_현지_날짜에는_다시_후보가_된다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);
        TestWorry target = viewed(guest, Duration.ofDays(3));
        client.post("/api/v1/reflections/skip-today?timeZone=" + ZONE);

        clock.advance(Duration.ofDays(1));

        Response response = candidate(client, current);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("worryId").asString()).isEqualTo(target.id().toString());
    }

    @Test
    void 같은_답을_다시_보내면_204_다른_답은_409이고_처음_답을_유지한다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);
        TestWorry target = viewed(guest, Duration.ofDays(2));
        assertThat(answer(client, target, current, "ONGOING").status()).isEqualTo(204);
        Instant answeredAt = clock.instant();
        clock.advance(Duration.ofMinutes(1));

        Response same = answer(client, target, current, "ONGOING");
        Response different = answer(client, target, current, "AS_FEARED");

        assertThat(same.status()).isEqualTo(204);
        assertThat(different.status()).isEqualTo(409);
        assertThat(different.code()).isEqualTo("REFLECTION_ALREADY_ANSWERED");
        Response stored = client.get("/api/v1/reflections/" + target.id());
        assertThat(stored.body().get("answer").asString()).isEqualTo("ONGOING");
        assertThat(OffsetDateTime.parse(stored.body().get("respondedAt").asString()).toInstant()).isEqualTo(answeredAt);
    }

    @Test
    void 후보가_아닌_기록에_답하면_409다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);
        TestWorry tooRecent = viewed(guest, Duration.ofHours(23));
        viewed(guest, Duration.ofDays(3));
        TestWorry notOldest = viewed(guest, Duration.ofDays(2));

        for (TestWorry target : new TestWorry[] {tooRecent, notOldest}) {
            Response response = answer(client, target, current, "BETTER");
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.code()).isEqualTo("REFLECTION_UNAVAILABLE");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM reflections").query(Long.class).single()).isZero();
    }

    @Test
    void 잠긴_기록은_409_다른_게스트_삭제_중_만료는_404다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);

        Response locked = answer(client, fixtures.lockedWorry(guest), current, "BETTER");
        assertThat(locked.status()).isEqualTo(409);
        assertThat(locked.code()).isEqualTo("WORRY_LOCKED");
        for (TestWorry hidden : new TestWorry[] {
            viewed(fixtures.guest(), Duration.ofDays(2)), fixtures.deletingWorry(guest), fixtures.expiredWorry(guest)}) {
            assertThat(answer(client, hidden, current, "BETTER").status()).isEqualTo(404);
            assertThat(client.get("/api/v1/reflections/" + hidden.id()).status()).isEqualTo(404);
        }
    }

    /** 공개 가능 시각이 {@code sincePublish} 전이고 열람을 마친 기록. */
    private TestWorry viewed(TestGuest guest, Duration sincePublish) {
        return fixtures.worry(guest, spec(sincePublish).withFirstViewedAt(clock.instant().minus(sincePublish).plusSeconds(1)));
    }

    private WorrySpec spec(Duration sincePublish) {
        Instant publish = clock.instant().minus(sincePublish);
        return WorrySpec.of(publish.minus(Duration.ofHours(10)), publish);
    }

    private Response candidate(Client client, TestWorry current) {
        return client.get("/api/v1/reflections/candidate?currentWorryId=" + current.id() + "&timeZone=" + ZONE);
    }

    private Response answer(Client client, TestWorry target, TestWorry current, String answer) {
        return client.put("/api/v1/reflections/" + target.id() + "?currentWorryId=" + current.id() + "&timeZone=" + ZONE,
            "{\"answer\":\"" + answer + "\"}");
    }

    private long skipRows(TestGuest guest) {
        return jdbc.sql("SELECT count(*) FROM reflection_skips WHERE guest_id = :guest").param("guest", guest.id())
            .query(Long.class).single();
    }
}
