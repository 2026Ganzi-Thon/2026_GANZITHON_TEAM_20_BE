package com.nightvault.backend.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import org.junit.jupiter.api.Test;

/** 도움 평가 (RESULT-02). 설계 §1-3, §4 #10. */
class ActivityFeedbackTest extends ActivityApiSupport {

    @Test
    void 완료한_액션_휴식_공감_모두_평가할_수_있다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);

        for (String helpType : List.of("ACTION", "REST", "EMPATHY")) {
            TestWorry worry = fixtures.publicWorry(guest);
            UUID activity = completedId(client, worry, helpType);

            Response saved = client.feedback(activity, "HELPFUL");

            assertThat(saved.status()).as(helpType).isEqualTo(200);
            assertThat(saved.cacheControl()).contains("no-store");
            assertThat(saved.body().get("activityId").asString()).isEqualTo(activity.toString());
            assertThat(saved.body().get("answer").asString()).isEqualTo("HELPFUL");
            assertThat(saved.body().get("answeredAt")).isEqualTo(saved.body().get("updatedAt"));
        }
    }

    @Test
    void 평가하지_않으면_기본값_없이_null이다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID empathy = startedId(client, worry, "EMPATHY");

        Response completed = client.complete(empathy);

        assertThat(completed.body().get("feedback").isNull()).isTrue();
        assertThat(client.activity(empathy).body().get("feedback").isNull()).isTrue();
        assertThat(feedbackRows(empathy)).isZero();
    }

    @Test
    void 저장한_평가는_활동_조회와_완료_재전송에_나온다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID empathy = completedId(client, worry, "EMPATHY");

        client.feedback(empathy, "UNSURE");

        assertThat(client.activity(empathy).body().get("feedback").get("answer").asString()).isEqualTo("UNSURE");
        assertThat(client.complete(empathy).body().get("feedback").get("answer").asString()).isEqualTo("UNSURE");
    }

    @Test
    void 다른_답을_보내면_덮어쓰고_최초_응답_시각은_유지한다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID action = completedId(client, worry, "ACTION");
        Response first = client.feedback(action, "HELPFUL");

        clock.advance(Duration.ofMinutes(5));
        Response changed = client.feedback(action, "NOT_HELPFUL");

        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.body().get("answer").asString()).isEqualTo("NOT_HELPFUL");
        assertThat(changed.body().get("answeredAt")).isEqualTo(first.body().get("answeredAt"));
        assertThat(changed.body().get("updatedAt")).isNotEqualTo(first.body().get("updatedAt"));
        assertThat(feedbackRows(action)).isOne();
    }

    @Test
    void 같은_답_재전송은_멱등이다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = completedId(client, worry, "REST");
        Response first = client.feedback(rest, "HELPFUL");

        clock.advance(Duration.ofMinutes(5));
        Response again = client.feedback(rest, "HELPFUL");

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(feedbackRows(rest)).isOne();
    }

    @Test
    void 완료하지_않은_활동은_409_ACTIVITY_NOT_COMPLETED() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);

        UUID inProgress = startedId(client, fixtures.publicWorry(guest), "ACTION");
        UUID paused = startedId(client, fixtures.publicWorry(guest), "REST");
        client.pause(paused);
        UUID stopped = startedId(client, fixtures.publicWorry(guest), "EMPATHY");
        client.stop(stopped);

        for (UUID activity : List.of(inProgress, paused, stopped)) {
            Response response = client.feedback(activity, "HELPFUL");
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.code()).isEqualTo("ACTIVITY_NOT_COMPLETED");
            assertThat(feedbackRows(activity)).isZero();
        }
    }

    @Test
    void 답이_틀리면_400이고_완료_상태는_그대로다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID empathy = completedId(client, worry, "EMPATHY");
        String path = "/api/v1/activities/" + empathy + "/feedback";

        for (String body : List.of("{}", "{\"answer\":\"helpful\"}", "{\"answer\":null}",
            "{\"answer\":\"HELPFUL\",\"x\":1}", "\"HELPFUL\"")) {
            Response response = client.put(path, body);
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.code()).as(body).isEqualTo("INVALID_INPUT");
        }
        assertThat(feedbackRows(empathy)).isZero();
        assertThat(client.activity(empathy).state()).isEqualTo("COMPLETED");
    }

    @Test
    void 다른_게스트의_완료_활동은_평가할_수_없다() {
        TestGuest owner = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(owner);
        UUID empathy = completedId(client(owner), worry, "EMPATHY");

        Response response = client(fixtures.guest()).feedback(empathy, "HELPFUL");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("NOT_FOUND");
        assertThat(feedbackRows(empathy)).isZero();
    }

    @Test
    void 걱정_삭제가_시작되면_평가를_저장하지_않고_활동_완료는_되돌리지_않는다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID empathy = completedId(client, worry, "EMPATHY");

        jdbc.sql("UPDATE worries SET deletion_started_at = now() WHERE id = :id").param("id", worry.id()).update();
        Response response = client.feedback(empathy, "HELPFUL");

        assertThat(response.status()).isEqualTo(404);
        assertThat(feedbackRows(empathy)).isZero();
        assertThat(storedState(empathy)).isEqualTo("COMPLETED");
    }

    @Test
    void 걱정이_만료되면_평가를_저장하지_않는다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID empathy = completedId(client, worry, "EMPATHY");
        client.feedback(empathy, "UNSURE");

        clock.advance(Duration.ofDays(28).plusMinutes(1));
        Response response = client.feedback(empathy, "HELPFUL");

        assertThat(response.status()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT answer FROM activity_feedback WHERE activity_id = :id").param("id", empathy)
            .query(String.class).single()).isEqualTo("UNSURE");
        assertThat(storedState(empathy)).isEqualTo("COMPLETED");
    }

    long feedbackRows(UUID activityId) {
        return jdbc.sql("SELECT count(*) FROM activity_feedback WHERE activity_id = :id").param("id", activityId)
            .query(Long.class).single();
    }

    String storedState(UUID activityId) {
        return jdbc.sql("SELECT state FROM activities WHERE id = :id").param("id", activityId)
            .query(String.class).single();
    }
}
