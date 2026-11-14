package com.nightvault.backend.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import com.nightvault.backend.support.WorryFixtures.WorrySpec;
import org.junit.jupiter.api.Test;

/** 중단·완료 (ACT-03, ACT-04, REST-04, EMP-02, RESULT-03). 설계 §2-2 "중단"·"완료" 열과 §2-4 경쟁 처리. */
class ActivityEndTest extends ActivityApiSupport {

    // ---- 액션 ----

    @Test
    void 액션은_세_단계를_모두_체크해야_완료된다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID action = startedId(client, worry, "ACTION");
        client.step(action, 1, true);
        client.step(action, 2, true);

        Response incomplete = client.complete(action);
        assertThat(incomplete.status()).isEqualTo(409);
        assertThat(incomplete.code()).isEqualTo("ACTION_STEPS_INCOMPLETE");
        assertThat(client.activity(action).state()).isEqualTo("IN_PROGRESS");

        client.step(action, 3, true);
        Response completed = client.complete(action);
        assertThat(completed.status()).isEqualTo(200);
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(completed.body().get("endedAt").asString()).isEqualTo(completed.body().get("serverTime").asString());
        assertThat(completed.body().get("action").get("canComplete").asBoolean()).isFalse();
    }

    @Test
    void 완료는_다시_보내도_멱등이고_완료_후_중단_체크는_409다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID action = startedId(client, worry, "ACTION");
        for (int stepNo = 1; stepNo <= 3; stepNo++) {
            client.step(action, stepNo, true);
        }
        Response first = client.complete(action);

        clock.advance(Duration.ofMinutes(1));
        Response again = client.complete(action);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("endedAt")).isEqualTo(first.body().get("endedAt"));
        assertThat(again.body().get("version")).isEqualTo(first.body().get("version"));

        for (Response response : new Response[] {client.stop(action), client.step(action, 1, false)}) {
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.code()).isEqualTo("ACTIVITY_ALREADY_ENDED");
        }
    }

    @Test
    void 완료한_액션을_다시_고르면_체크가_빈_새_행이다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID action = startedId(client, worry, "ACTION");
        for (int stepNo = 1; stepNo <= 3; stepNo++) {
            client.step(action, stepNo, true);
        }
        client.complete(action);

        Response again = start(client, worry, "ACTION");

        assertThat(again.status()).isEqualTo(201);
        assertThat(again.activityId()).isNotEqualTo(action);
        assertThat(again.body().get("action").get("doneCount").asInt()).isZero();
    }

    @Test
    void 나중에_이어하기는_체크를_유지하고_다시_시작하면_같은_행을_재개한다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        Response started = start(client, worry, "ACTION");
        UUID action = started.activityId();
        client.step(action, 2, true);

        Response stopped = client.stop(action);
        assertThat(stopped.status()).isEqualTo(200);
        assertThat(stopped.state()).isEqualTo("STOPPED");
        assertThat(stopped.body().get("action").get("steps").get(1).get("done").asBoolean()).isTrue();

        Response stoppedAgain = client.stop(action);
        assertThat(stoppedAgain.status()).isEqualTo(200);
        assertThat(stoppedAgain.body().get("version")).isEqualTo(stopped.body().get("version"));

        Response completeStopped = client.complete(action);
        assertThat(completeStopped.status()).isEqualTo(409);
        assertThat(completeStopped.code()).isEqualTo("ACTIVITY_ALREADY_ENDED");

        // 열린 활동이 없으므로 replaceOpen 없이 다른 도움도 바로 시작할 수 있다
        clock.advance(Duration.ofMinutes(3));
        Response resumed = start(client, worry, "ACTION");
        assertThat(resumed.status()).isEqualTo(200);
        assertThat(resumed.activityId()).isEqualTo(action);
        assertThat(resumed.state()).isEqualTo("IN_PROGRESS");
        assertThat(resumed.body().get("startedAt")).isEqualTo(started.body().get("startedAt"));
        assertThat(resumed.body().get("action").get("doneCount").asInt()).isOne();
    }

    // ---- 휴식 ----

    @Test
    void 휴식은_영초_전에는_완료할_수_없고_영초_이후_사용자가_완료한다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = startedId(client, worry, "REST");

        clock.advance(Duration.ofSeconds(179));
        Response early = client.complete(rest);
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.code()).isEqualTo("REST_NOT_FINISHED");

        clock.advance(Duration.ofSeconds(1));
        Response completed = client.complete(rest);
        assertThat(completed.status()).isEqualTo(200);
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(completed.body().get("rest").get("endsAt").isNull()).isTrue();
        assertThat(completed.body().get("rest").get("timeOver").asBoolean()).isFalse();
    }

    @Test
    void 일시정지_중인_휴식은_완료할_수_없다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = startedId(client, worry, "REST");
        client.pause(rest);
        clock.advance(Duration.ofMinutes(10));

        Response response = client.complete(rest);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("REST_NOT_FINISHED");
        assertThat(client.activity(rest).state()).isEqualTo("PAUSED");
    }

    @Test
    void 휴식_그만두기는_남은_시간을_버리고_다시_시작하면_새_3분이다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = startedId(client, worry, "REST");
        clock.advance(Duration.ofSeconds(100));

        Response stopped = client.stop(rest);
        assertThat(stopped.state()).isEqualTo("STOPPED");
        assertThat(stopped.body().get("rest").get("endsAt").isNull()).isTrue();
        assertThat(stopped.body().get("rest").get("remainingSeconds").isNull()).isTrue();

        Response again = start(client, worry, "REST");
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.activityId()).isNotEqualTo(rest);
        assertThat(again.body().get("rest").get("remainingSeconds").asInt()).isEqualTo(180);
    }

    // ---- 공감 ----

    @Test
    void 공감은_읽었어요로_바로_완료된다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID empathy = startedId(client, worry, "EMPATHY");

        Response completed = client.complete(empathy);

        assertThat(completed.status()).isEqualTo(200);
        assertThat(completed.state()).isEqualTo("COMPLETED");
        assertThat(completed.body().get("endedAt").isNull()).isFalse();
    }

    // ---- RESULT-03 ----

    @Test
    void 완료_응답에_다음_미확인_걱정_여부를_넣는다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        fixtures.lockedWorry(guest);
        fixtures.deletingWorry(guest);
        fixtures.expiredWorry(guest);
        Instant created = Instant.now().minus(Duration.ofDays(3));
        fixtures.worry(guest, WorrySpec.of(created, created.plus(Duration.ofDays(1)))
            .withFirstViewedAt(created.plus(Duration.ofDays(2))));
        fixtures.publicWorry(fixtures.guest());  // 다른 게스트 것

        UUID first = startedId(client, worry, "EMPATHY");
        Response none = client.complete(first);
        assertThat(none.body().get("nextUnviewedExists").asBoolean()).isFalse();

        fixtures.publicWorry(guest);
        assertThat(client.complete(first).body().get("nextUnviewedExists").asBoolean()).isTrue();
        // 완료 외 응답에는 없다
        assertThat(client.activity(first).body().get("nextUnviewedExists").isNull()).isTrue();
    }

    // ---- 경쟁 (§2-4) ----

    @Test
    void 완료와_중단이_동시에_오면_먼저_커밋한_쪽이_이기고_나머지는_409다() throws Exception {
        TestGuest guest = fixtures.guest();
        for (int round = 0; round < 5; round++) {
            TestWorry worry = fixtures.publicWorry(guest);
            UUID empathy = service.start(guest.id(), worry.id(), ActivityRow.HelpType.EMPATHY, false).view().activityId();

            List<Outcome> outcomes = race(
                () -> service.complete(guest.id(), empathy),
                () -> service.stop(guest.id(), empathy));

            assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
            Outcome loser = outcomes.stream().filter(outcome -> !outcome.succeeded()).findFirst().orElseThrow();
            assertThat(loser.error()).isEqualTo(ApiError.ACTIVITY_ALREADY_ENDED);

            String winnerState = outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow().state();
            ActivityView stored = service.get(guest.id(), empathy);
            assertThat(stored.state().name()).isEqualTo(winnerState);
            assertThat(stored.version()).isOne();
        }
    }

    @Test
    void 같은_완료가_동시에_와도_둘_다_200이고_한_번만_바뀐다() throws Exception {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        UUID empathy = service.start(guest.id(), worry.id(), ActivityRow.HelpType.EMPATHY, false).view().activityId();

        List<Outcome> outcomes = race(
            () -> service.complete(guest.id(), empathy),
            () -> service.complete(guest.id(), empathy));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(service.get(guest.id(), empathy).version()).isOne();
    }

    record Outcome(String state, ApiError error) {
        boolean succeeded() {
            return error == null;
        }
    }

    /** 두 호출을 최대한 같은 순간에 시작한다. */
    private List<Outcome> race(Callable<ActivityView> first, Callable<ActivityView> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ActivityView>> futures = new ArrayList<>();
            for (Callable<ActivityView> call : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<ActivityView> future : futures) {
                try {
                    outcomes.add(new Outcome(future.get().state().name(), null));
                } catch (ExecutionException failure) {
                    if (!(failure.getCause() instanceof ActivityException exception)) {
                        throw failure;
                    }
                    outcomes.add(new Outcome(null, exception.error()));
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
