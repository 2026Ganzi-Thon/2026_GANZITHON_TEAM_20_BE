package com.nightvault.backend.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nightvault.backend.activity.ActivityRow.HelpType;
import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import org.junit.jupiter.api.Test;

/** 시작·재진입·도움 변경 (M-05, ACT-01, ACT-03 재개, REST-01, EMP-02). 설계 §2-2 "시작 전"·"시작/진입" 열과 §2-3·§2-4. */
class ActivityStartTest extends ActivityApiSupport {

    @Test
    void 액션을_시작하면_201과_빈_체크의_진행_중_활동을_만든다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);

        Response response = start(client(guest), worry, "ACTION");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.location()).isEqualTo("/api/v1/activities/" + response.activityId());
        assertThat(response.cacheControl()).contains("no-store");
        assertThat(response.state()).isEqualTo("IN_PROGRESS");
        assertThat(response.body().get("helpType").asString()).isEqualTo("ACTION");
        assertThat(response.body().get("version").asInt()).isZero();
        assertThat(response.body().get("endedAt").isNull()).isTrue();
        assertThat(response.body().get("action").get("doneCount").asInt()).isZero();
        assertThat(response.body().get("action").get("canComplete").asBoolean()).isFalse();
        assertThat(response.body().get("rest").isNull()).isTrue();
        assertThat(response.body().get("stoppedActivityId").isNull()).isTrue();
    }

    @Test
    void 휴식을_시작하면_서버_시각_기준_180초_타이머가_돈다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);

        Response response = start(client(guest), worry, "REST");

        assertThat(response.status()).isEqualTo(201);
        var rest = response.body().get("rest");
        Instant serverTime = Instant.parse(response.body().get("serverTime").asString());
        assertThat(rest.get("durationSeconds").asInt()).isEqualTo(180);
        assertThat(rest.get("remainingSeconds").asInt()).isEqualTo(180);
        assertThat(rest.get("timeOver").asBoolean()).isFalse();
        assertThat(Instant.parse(rest.get("endsAt").asString())).isEqualTo(serverTime.plusSeconds(180));
        assertThat(response.body().get("action").isNull()).isTrue();
    }

    @Test
    void 공감을_시작하면_진행_중_활동을_만든다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);

        Response response = start(client(guest), worry, "EMPATHY");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.state()).isEqualTo("IN_PROGRESS");
        assertThat(response.body().get("action").isNull()).isTrue();
        assertThat(response.body().get("rest").isNull()).isTrue();
    }

    @Test
    void 같은_유형_재전송은_기존_활동을_200으로_반환하고_시작_시각을_유지한다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        Response first = start(client, worry, "REST");

        clock.advance(Duration.ofSeconds(30));
        Response again = start(client, worry, "REST");

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.location()).isNull();
        assertThat(again.activityId()).isEqualTo(first.activityId());
        assertThat(again.body().get("startedAt")).isEqualTo(first.body().get("startedAt"));
        assertThat(again.body().get("rest").get("remainingSeconds").asInt()).isEqualTo(150);
        assertThat(activityCount(worry)).isOne();
    }

    @Test
    void 동시에_시작해도_열린_활동은_하나다() throws Exception {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        int threads = 6;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<ActivityService.StartResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                go.await();
                return service.start(guest.id(), worry.id(), HelpType.ACTION, false);
            });
        }

        List<ActivityService.StartResult> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ActivityService.StartResult>> futures = new ArrayList<>();
            for (var task : tasks) {
                futures.add(pool.submit(task));
            }
            ready.await();
            go.countDown();
            for (var future : futures) {
                results.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(results).filteredOn(ActivityService.StartResult::created).hasSize(1);
        assertThat(results).extracting(result -> result.view().activityId()).containsOnly(results.get(0).view().activityId());
        assertThat(activityCount(worry)).isOne();
    }

    @Test
    void 다른_유형이_열려_있으면_409이고_기존_활동은_그대로다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = startedId(client, worry, "REST");

        Response response = start(client, worry, "ACTION");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("OPEN_ACTIVITY_EXISTS");
        Response current = client.activity(rest);
        assertThat(current.state()).isEqualTo("IN_PROGRESS");
        assertThat(current.body().get("version").asInt()).isZero();
        assertThat(activityCount(worry)).isOne();
    }

    @Test
    void 도움_변경은_기존_휴식을_중단하고_새_활동을_시작한다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = startedId(client, worry, "REST");

        Response response = replace(client, worry, "EMPATHY");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("helpType").asString()).isEqualTo("EMPATHY");
        assertThat(response.body().get("stoppedActivityId").asString()).isEqualTo(rest.toString());

        Response stopped = client.activity(rest);
        assertThat(stopped.state()).isEqualTo("STOPPED");
        assertThat(stopped.body().get("endedAt").isNull()).isFalse();
        assertThat(stopped.body().get("rest").get("endsAt").isNull()).isTrue();
        assertThat(stopped.body().get("rest").get("remainingSeconds").isNull()).isTrue();
    }

    @Test
    void 도움_변경으로_중단한_액션은_체크를_유지하고_다시_고르면_같은_행을_재개한다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        Response action = start(client, worry, "ACTION");
        UUID actionId = action.activityId();
        jdbc.sql("UPDATE activities SET step2_done = true WHERE id = :id").param("id", actionId).update();

        clock.advance(Duration.ofMinutes(1));
        Response rest = replace(client, worry, "REST");
        assertThat(rest.body().get("stoppedActivityId").asString()).isEqualTo(actionId.toString());
        assertThat(client.activity(actionId).state()).isEqualTo("STOPPED");

        clock.advance(Duration.ofMinutes(1));
        Response resumed = replace(client, worry, "ACTION");

        assertThat(resumed.status()).isEqualTo(200);
        assertThat(resumed.activityId()).isEqualTo(actionId);
        assertThat(resumed.state()).isEqualTo("IN_PROGRESS");
        assertThat(resumed.body().get("startedAt")).isEqualTo(action.body().get("startedAt"));
        assertThat(resumed.body().get("endedAt").isNull()).isTrue();
        assertThat(resumed.body().get("action").get("steps").get(1).get("done").asBoolean()).isTrue();
        assertThat(resumed.body().get("stoppedActivityId").asString()).isEqualTo(rest.activityId().toString());
        assertThat(client.activity(rest.activityId()).state()).isEqualTo("STOPPED");
        assertThat(activityCount(worry)).isEqualTo(2);
    }

    @Test
    void 중단된_휴식을_다시_고르면_새_행으로_새_3분을_시작한다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID first = startedId(client, worry, "REST");
        clock.advance(Duration.ofSeconds(100));
        replace(client, worry, "EMPATHY");

        Response again = replace(client, worry, "REST");

        assertThat(again.status()).isEqualTo(201);
        assertThat(again.activityId()).isNotEqualTo(first);
        assertThat(again.body().get("rest").get("remainingSeconds").asInt()).isEqualTo(180);
        assertThat(activityCount(worry)).isEqualTo(3);
    }

    @Test
    void 도움_유형이_틀리거나_파쇄면_400() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        String path = "/api/v1/worries/" + worry.id() + "/activities";

        for (String body : List.of(
            "{\"helpType\":\"SHRED\"}",
            "{\"helpType\":\"action\"}",
            "{}",
            "{\"helpType\":\"ACTION\",\"replaceOpen\":\"yes\"}",
            "{\"helpType\":\"ACTION\",\"extra\":1}",
            "[]",
            "not-json")) {
            Response response = client.post(path, body);
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.code()).as(body).isEqualTo("INVALID_INPUT");
        }
        assertThat(client.post("/api/v1/worries/not-a-uuid/activities", "{\"helpType\":\"ACTION\"}").status())
            .isEqualTo(400);
        assertThat(activityCount(worry)).isZero();
    }
}
