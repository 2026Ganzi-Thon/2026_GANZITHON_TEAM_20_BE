package com.nightvault.backend.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import org.junit.jupiter.api.Test;

/** 휴식 일시정지·재개·복원·0초 (REST-02, REST-03). 설계 §1-2 휴식 타이머 계산, §2-2 "일시정지"·"재개"·"화면 이탈" 열. */
class ActivityRestTimerTest extends ActivityApiSupport {

    @Test
    void 화면을_떠나도_실행_중이면_시간이_흐른다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = startedId(client, worry, "REST");

        clock.advance(Duration.ofSeconds(70));
        Response restored = client.activity(rest);

        assertThat(restored.state()).isEqualTo("IN_PROGRESS");
        assertThat(remaining(restored)).isEqualTo(110);
        assertThat(restored.body().get("rest").get("timeOver").asBoolean()).isFalse();
    }

    @Test
    void 남은_시간은_올림한다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = startedId(client, worry, "REST");

        clock.advance(Duration.ofMillis(10_400));

        assertThat(remaining(client.activity(rest))).isEqualTo(170);
    }

    @Test
    void 일시정지하면_남은_시간이_멈추고_재개하면_종료_시각을_다시_계산한다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = startedId(client, worry, "REST");

        clock.advance(Duration.ofSeconds(60));
        Response paused = client.pause(rest);
        assertThat(paused.status()).isEqualTo(200);
        assertThat(paused.state()).isEqualTo("PAUSED");
        assertThat(paused.body().get("rest").get("endsAt").isNull()).isTrue();
        assertThat(remaining(paused)).isEqualTo(120);

        // 정지 중 이탈: 그대로 멈춰 있다
        clock.advance(Duration.ofMinutes(10));
        assertThat(remaining(client.activity(rest))).isEqualTo(120);

        Response resumed = client.resume(rest);
        assertThat(resumed.state()).isEqualTo("IN_PROGRESS");
        Instant serverTime = Instant.parse(resumed.body().get("serverTime").asString());
        assertThat(Instant.parse(resumed.body().get("rest").get("endsAt").asString()))
            .isEqualTo(serverTime.plusSeconds(120));
        assertThat(remaining(resumed)).isEqualTo(120);

        clock.advance(Duration.ofSeconds(20));
        assertThat(remaining(client.activity(rest))).isEqualTo(100);
    }

    @Test
    void 일시정지와_재개는_같은_상태로_다시_보내면_멱등이다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = startedId(client, worry, "REST");

        Response resumedWhileRunning = client.resume(rest);
        assertThat(resumedWhileRunning.status()).isEqualTo(200);
        assertThat(resumedWhileRunning.body().get("version").asInt()).isZero();

        client.pause(rest);
        clock.advance(Duration.ofSeconds(30));
        Response pausedAgain = client.pause(rest);
        assertThat(pausedAgain.status()).isEqualTo(200);
        assertThat(pausedAgain.state()).isEqualTo("PAUSED");
        assertThat(pausedAgain.body().get("version").asInt()).isOne();
        assertThat(remaining(pausedAgain)).isEqualTo(180);
    }

    @Test
    void 영초가_되면_timeOver만_표시하고_상태는_바꾸지_않는다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = startedId(client, worry, "REST");

        clock.advance(Duration.ofMinutes(5));
        Response restored = client.activity(rest);

        assertThat(restored.state()).isEqualTo("IN_PROGRESS");
        assertThat(remaining(restored)).isZero();
        assertThat(restored.body().get("rest").get("timeOver").asBoolean()).isTrue();
    }

    @Test
    void 영초_이후에는_일시정지할_수_없다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = startedId(client, worry, "REST");

        clock.advance(Duration.ofSeconds(180));
        Response response = client.pause(rest);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("REST_TIME_OVER");
        assertThat(client.activity(rest).state()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void 휴식이_아니면_일시정지와_재개는_409_INVALID_ACTIVITY_STATE() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID action = startedId(client, worry, "ACTION");

        for (Response response : new Response[] {client.pause(action), client.resume(action)}) {
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.code()).isEqualTo("INVALID_ACTIVITY_STATE");
        }
    }

    @Test
    void 일시정지_중_도움을_바꾸면_남은_시간을_버리고_다시_고르면_새_3분이다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = startedId(client, worry, "REST");
        clock.advance(Duration.ofSeconds(90));
        client.pause(rest);

        replace(client, worry, "ACTION");

        Response stopped = client.activity(rest);
        assertThat(stopped.state()).isEqualTo("STOPPED");
        assertThat(stopped.body().get("rest").get("remainingSeconds").isNull()).isTrue();
        for (Response response : new Response[] {client.pause(rest), client.resume(rest)}) {
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.code()).isEqualTo("ACTIVITY_ALREADY_ENDED");
        }

        Response again = replace(client, worry, "REST");
        assertThat(again.status()).isEqualTo(201);
        assertThat(remaining(again)).isEqualTo(180);
    }

    static int remaining(Response response) {
        return response.body().get("rest").get("remainingSeconds").asInt();
    }
}
