package com.nightvault.backend.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import org.junit.jupiter.api.Test;

/** 액션 체크 저장·해제 (ACT-02). 설계 §2-2 "체크 변경" 열. */
class ActivityStepTest extends ActivityApiSupport {

    @Test
    void 순서와_상관없이_체크하고_해제할_수_있다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID action = startedId(client, worry, "ACTION");

        Response third = client.step(action, 3, true);
        assertThat(third.status()).isEqualTo(200);
        assertThat(doneFlags(third)).containsExactly(false, false, true);
        assertThat(third.body().get("action").get("doneCount").asInt()).isOne();
        assertThat(third.body().get("version").asInt()).isOne();

        client.step(action, 1, true);
        Response all = client.step(action, 2, true);
        assertThat(doneFlags(all)).containsExactly(true, true, true);
        assertThat(all.body().get("action").get("canComplete").asBoolean()).isTrue();

        Response unchecked = client.step(action, 1, false);
        assertThat(doneFlags(unchecked)).containsExactly(false, true, true);
        assertThat(unchecked.body().get("action").get("canComplete").asBoolean()).isFalse();
        assertThat(unchecked.state()).isEqualTo("IN_PROGRESS");

        // 다시 읽어도 같은 값(서버 저장)
        assertThat(doneFlags(client.activity(action))).containsExactly(false, true, true);
    }

    @Test
    void 같은_값_재전송은_멱등이다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID action = startedId(client, worry, "ACTION");

        client.step(action, 2, true);
        Response again = client.step(action, 2, true);

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("version").asInt()).isOne();
        assertThat(doneFlags(again)).containsExactly(false, true, false);
    }

    @Test
    void 액션이_아니면_409_INVALID_ACTIVITY_STATE() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = startedId(client, worry, "REST");

        Response response = client.step(rest, 1, true);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("INVALID_ACTIVITY_STATE");
    }

    @Test
    void 중단된_액션은_체크할_수_없고_체크는_그대로다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID action = startedId(client, worry, "ACTION");
        client.step(action, 1, true);
        replace(client, worry, "EMPATHY");

        Response response = client.step(action, 2, true);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("ACTIVITY_ALREADY_ENDED");
        assertThat(doneFlags(client.activity(action))).containsExactly(true, false, false);
    }

    @Test
    void 단계_번호나_본문이_틀리면_400() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID action = startedId(client, worry, "ACTION");
        String base = "/api/v1/activities/" + action + "/steps/";

        for (String stepNo : List.of("0", "4", "01", "x")) {
            assertThat(client.put(base + stepNo, "{\"done\":true}").status()).as(stepNo).isEqualTo(400);
        }
        for (String body : List.of("{}", "{\"done\":\"true\"}", "{\"done\":true,\"x\":1}", "true")) {
            assertThat(client.put(base + "1", body).status()).as(body).isEqualTo(400);
        }
        assertThat(client.activity(action).body().get("version").asInt()).isZero();
    }

    static List<Boolean> doneFlags(Response response) {
        var steps = response.body().get("action").get("steps");
        return List.of(steps.get(0).get("done").asBoolean(), steps.get(1).get("done").asBoolean(),
            steps.get(2).get("done").asBoolean());
    }
}
