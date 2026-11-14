package com.nightvault.backend.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import org.junit.jupiter.api.Test;

/** 홈 이어하기 (HOME-03). 설계 §2-5, §3-5. */
class ActivityCurrentTest extends ActivityApiSupport {

    @Test
    void 이어할_활동이_없으면_204() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);

        Response empty = client.current();
        assertThat(empty.status()).isEqualTo(204);
        assertThat(empty.body()).isNull();
        assertThat(empty.cacheControl()).contains("no-store");

        // 완료·중단만 있어도 204
        completedId(client, fixtures.publicWorry(guest), "EMPATHY");
        UUID stopped = startedId(client, fixtures.publicWorry(guest), "ACTION");
        client.stop(stopped);
        assertThat(client.current().status()).isEqualTo(204);
    }

    @Test
    void 여러_활동_중_상태가_가장_최근에_바뀐_것을_고른다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        UUID action = startedId(client, fixtures.publicWorry(guest), "ACTION");
        clock.advance(Duration.ofMinutes(1));
        UUID rest = startedId(client, fixtures.publicWorry(guest), "REST");
        clock.advance(Duration.ofMinutes(1));
        UUID empathy = startedId(client, fixtures.publicWorry(guest), "EMPATHY");

        assertThat(client.current().activityId()).isEqualTo(empathy);

        // 완료되면 빠지고 그다음 최근 것
        clock.advance(Duration.ofMinutes(1));
        client.complete(empathy);
        assertThat(client.current().activityId()).isEqualTo(rest);

        // 체크 변경은 상태 변경이 아니라 순서를 바꾸지 않는다
        clock.advance(Duration.ofMinutes(1));
        client.step(action, 1, true);
        assertThat(client.current().activityId()).isEqualTo(rest);

        // 중단되면 빠진다
        client.stop(rest);
        assertThat(client.current().activityId()).isEqualTo(action);
    }

    @Test
    void 일시정지도_상태_변경이라_가장_최근이_된다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        UUID rest = startedId(client, fixtures.publicWorry(guest), "REST");
        clock.advance(Duration.ofSeconds(40));
        startedId(client, fixtures.publicWorry(guest), "ACTION");
        clock.advance(Duration.ofSeconds(20));

        client.pause(rest);
        Response current = client.current();

        assertThat(current.activityId()).isEqualTo(rest);
        assertThat(current.state()).isEqualTo("PAUSED");
        assertThat(current.body().get("progress").get("remainingSeconds").asInt()).isEqualTo(120);
        assertThat(current.body().get("progress").get("timeOver").asBoolean()).isFalse();
    }

    @Test
    void 응답은_유형별_진행_정보만_담고_원문과_요약은_없다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry worry = fixtures.publicWorry(guest);
        UUID action = startedId(client, worry, "ACTION");
        client.step(action, 2, true);

        Response current = client.current();

        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("worryId").asString()).isEqualTo(worry.id().toString());
        assertThat(current.body().get("helpType").asString()).isEqualTo("ACTION");
        assertThat(current.body().get("serverTime").isNull()).isFalse();
        assertThat(current.body().get("progress").get("doneCount").asInt()).isOne();
        assertThat(current.body().get("progress").get("totalSteps").asInt()).isEqualTo(3);
        assertThat(current.body().propertyNames())
            .containsExactlyInAnyOrder("activityId", "worryId", "helpType", "state", "serverTime", "progress");

        replace(client, worry, "EMPATHY");
        assertThat(client.current().body().get("progress").isNull()).isTrue();

        replace(client, worry, "REST");
        clock.advance(Duration.ofMinutes(4));
        Response restOver = client.current();
        assertThat(restOver.body().get("progress").get("remainingSeconds").asInt()).isZero();
        assertThat(restOver.body().get("progress").get("timeOver").asBoolean()).isTrue();
    }

    @Test
    void 다른_게스트의_활동은_나오지_않는다() {
        TestGuest owner = fixtures.guest();
        startedId(client(owner), fixtures.publicWorry(owner), "ACTION");

        assertThat(client(fixtures.guest()).current().status()).isEqualTo(204);
    }

    @Test
    void 삭제_중이거나_만료된_걱정의_활동은_나오지_않는다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry older = fixtures.publicWorry(guest);
        UUID olderAction = startedId(client, older, "ACTION");
        clock.advance(Duration.ofMinutes(1));
        TestWorry deleting = fixtures.publicWorry(guest);
        startedId(client, deleting, "REST");

        jdbc.sql("UPDATE worries SET deletion_started_at = now() WHERE id = :id").param("id", deleting.id()).update();
        assertThat(client.current().activityId()).isEqualTo(olderAction);

        clock.advance(Duration.ofDays(28).plusMinutes(1));
        assertThat(client.current().status()).isEqualTo(204);
    }
}
