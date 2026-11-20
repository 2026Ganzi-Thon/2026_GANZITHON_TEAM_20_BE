package com.nightvault.backend.deletion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.nightvault.backend.support.ApiTestSupport;
import com.nightvault.backend.support.WorryFixtures;
import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import com.nightvault.backend.support.WorryFixtures.WorrySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 개별 삭제·전체 삭제·만료 정리 (H-02, LOCK-02, R-04, D-01·02, SET-05). */
class VaultDeletionApiTest extends ApiTestSupport {

    @Autowired
    VaultDeletion deletion;

    @Test
    void 공개된_걱정을_삭제하면_연결_데이터까지_지우고_204다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry worry = fixtures.publicWorry(guest);
        client.post("/api/v1/worries/" + worry.id() + "/activities", "{\"helpType\":\"EMPATHY\"}");

        Response deleted = client.delete(path(worry));

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(deleted.cacheControl()).contains("no-store");
        assertThat(count("worries WHERE id = '" + worry.id() + "'")).isZero();
        assertThat(count("ai_contents WHERE worry_id = '" + worry.id() + "'")).isZero();
        assertThat(count("activities WHERE worry_id = '" + worry.id() + "'")).isZero();
        assertThat(count("worry_deletion_receipts WHERE worry_id = '" + worry.id() + "'")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT state FROM analysis_requests WHERE id = :id").param("id", worry.requestId())
            .query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(client.get("/api/v1/worries/" + worry.id()).status()).isEqualTo(404);
    }

    @Test
    void 이미_삭제한_걱정을_다시_삭제하면_204다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry worry = fixtures.publicWorry(guest);
        client.delete(path(worry));

        assertThat(client.delete(path(worry)).status()).isEqualTo(204);
    }

    @Test
    void 잠긴_걱정도_같은_범위로_삭제한다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry worry = fixtures.lockedWorry(guest);

        assertThat(client.delete(path(worry)).status()).isEqualTo(204);
        assertThat(count("worries WHERE id = '" + worry.id() + "'")).isZero();
    }

    @Test
    void 만료됐지만_정리_전인_걱정을_삭제하면_정리하고_204다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry worry = fixtures.expiredWorry(guest);

        Response first = client.delete(path(worry));
        Response again = client.delete(path(worry));

        assertThat(first.status()).isEqualTo(204);
        assertThat(again.status()).isEqualTo(204);
        assertThat(count("worries WHERE id = '" + worry.id() + "'")).isZero();
    }

    @Test
    void 정리_작업이_지운_만료_걱정을_삭제하면_204다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry worry = fixtures.expiredWorry(guest);
        deletion.cleanUp();

        assertThat(client.delete(path(worry)).status()).isEqualTo(204);
    }

    @Test
    void 삭제가_시작된_걱정을_다시_삭제하면_정리를_마치고_204다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry worry = fixtures.deletingWorry(guest);

        assertThat(client.delete(path(worry)).status()).isEqualTo(204);
        assertThat(count("worries WHERE id = '" + worry.id() + "'")).isZero();
    }

    @Test
    void 정리가_실패하면_202이고_조회는_막히며_재처리_후_204다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry worry = fixtures.publicWorry(guest);
        failDeletesOf(guest);
        try {
            Response pending = client.delete(path(worry));

            assertThat(pending.status()).isEqualTo(202);
            assertThat(pending.body().get("status").asString()).isEqualTo("DELETING");
            assertThat(client.get("/api/v1/worries/" + worry.id()).status()).isEqualTo(404);
            assertThat(client.get("/api/v1/records/" + worry.id()).status()).isEqualTo(404);
            assertThat(client.delete(path(worry)).status()).isEqualTo(202);
        } finally {
            dropFailingTrigger();
        }

        deletion.cleanUp();

        assertThat(client.delete(path(worry)).status()).isEqualTo(204);
    }

    @Test
    void 다른_게스트의_걱정과_없는_걱정은_404다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry others = fixtures.publicWorry(fixtures.guest());

        Response response = client.delete(path(others));

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("NOT_FOUND");
        assertThat(count("worries WHERE id = '" + others.id() + "' AND deletion_started_at IS NULL")).isEqualTo(1);
        assertThat(client.delete("/api/v1/worries/" + UUID.randomUUID()).status()).isEqualTo(404);
    }

    @Test
    void 다른_게스트가_지운_걱정의_삭제_이력은_보이지_않는다() {
        TestGuest owner = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(owner);
        client(owner).delete(path(worry));

        assertThat(client(fixtures.guest()).delete(path(worry)).status()).isEqualTo(404);
    }

    @Test
    void 삭제_이력은_30일이_지나면_404다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry worry = fixtures.publicWorry(guest);
        client.delete(path(worry));

        clock.advance(Duration.ofDays(30).minusSeconds(1));
        assertThat(client.delete(path(worry)).status()).isEqualTo(204);
        clock.advance(Duration.ofSeconds(1));
        assertThat(client.delete(path(worry)).status()).isEqualTo(404);
    }

    @Test
    void 정리_작업은_만료된_걱정만_지운다() {
        TestGuest guest = fixtures.guest();
        TestWorry expired = fixtures.expiredWorry(guest);
        Instant created = WorryFixtures.now().minus(Duration.ofDays(30)).plusSeconds(1);
        TestWorry almost = fixtures.worry(guest, WorrySpec.of(created, created.plus(Duration.ofDays(1))));

        deletion.cleanUp();

        assertThat(count("worries WHERE id = '" + expired.id() + "'")).isZero();
        assertThat(count("worry_deletion_receipts WHERE worry_id = '" + expired.id() + "'")).isEqualTo(1);
        assertThat(count("worries WHERE id = '" + almost.id() + "'")).isEqualTo(1);
    }

    @Test
    void 정리하지_못한_행이_100건_넘게_앞에_있어도_뒤_행을_정리한다() {
        TestGuest blocked = fixtures.guest();
        Instant created = WorryFixtures.now().minus(Duration.ofDays(40));
        for (int i = 0; i < 101; i++) {
            fixtures.worry(blocked, WorrySpec.of(created.plusSeconds(i), created.plusSeconds(i).plus(Duration.ofDays(1))));
        }
        TestWorry later = fixtures.expiredWorry(fixtures.guest());
        failDeletesOf(blocked);
        try {
            deletion.cleanUp();
        } finally {
            dropFailingTrigger();
        }

        assertThat(count("worries WHERE id = '" + later.id() + "'")).isZero();
        assertThat(count("worries WHERE guest_id = '" + blocked.id() + "'")).isEqualTo(101);
    }

    @Test
    void 비활성_게스트의_행은_정리_대상에서_빠지고_다른_행을_막지_않는다() {
        TestGuest inactive = fixtures.guest();
        Instant created = WorryFixtures.now().minus(Duration.ofDays(40));
        for (int i = 0; i < 101; i++) {
            fixtures.worry(inactive, WorrySpec.of(created.plusSeconds(i), created.plusSeconds(i).plus(Duration.ofDays(1))));
        }
        jdbc.sql("UPDATE guests SET state = 'DELETING' WHERE id = :id").param("id", inactive.id()).update();
        TestWorry later = fixtures.expiredWorry(fixtures.guest());

        deletion.cleanUp();

        assertThat(count("worries WHERE id = '" + later.id() + "'")).isZero();
    }

    @Test
    void 전체_삭제는_게스트와_모든_기록을_지우고_인증을_없앤다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        fixtures.publicWorry(guest);
        fixtures.lockedWorry(guest);

        Response deleted = client.delete("/api/v1/vault");

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(count("guests WHERE id = '" + guest.id() + "'")).isZero();
        assertThat(count("worries WHERE guest_id = '" + guest.id() + "'")).isZero();
        assertThat(client.get("/api/v1/vault/summary").status()).isEqualTo(401);
    }

    private static String path(TestWorry worry) {
        return "/api/v1/worries/" + worry.id();
    }

    private long count(String fromWhere) {
        return jdbc.sql("SELECT count(*) FROM " + fromWhere).query(Long.class).single();
    }

    /** 이 게스트의 걱정 행 삭제를 DB에서 실패시킨다(부분 실패 재현). */
    private void failDeletesOf(TestGuest guest) {
        jdbc.sql("""
            CREATE OR REPLACE FUNCTION test_fail_worry_delete() RETURNS trigger AS $$
            BEGIN RAISE EXCEPTION 'test delete failure'; END $$ LANGUAGE plpgsql
            """).update();
        jdbc.sql("CREATE TRIGGER test_fail_worry_delete BEFORE DELETE ON worries FOR EACH ROW WHEN (OLD.guest_id = '"
            + guest.id() + "') EXECUTE FUNCTION test_fail_worry_delete()").update();
    }

    private void dropFailingTrigger() {
        jdbc.sql("DROP TRIGGER IF EXISTS test_fail_worry_delete ON worries").update();
        jdbc.sql("DROP FUNCTION IF EXISTS test_fail_worry_delete()").update();
    }
}
