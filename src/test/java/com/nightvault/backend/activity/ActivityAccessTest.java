package com.nightvault.backend.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.nightvault.backend.activity.ActivityRow.HelpType;
import com.nightvault.backend.api.ApiError;
import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** 소유권·공개 여부·삭제·만료 검사(A-06, 설계 §3 공통 조건). */
class ActivityAccessTest extends ActivityApiSupport {

    @Test
    void 인증_없이_부르면_401_CSRF_없이_쓰면_403() throws Exception {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);

        var anonymous = mvc.perform(MockMvcRequestBuilders.get("/api/v1/activities/" + UUID.randomUUID())).andReturn().getResponse();
        assertThat(anonymous.getStatus()).isEqualTo(401);

        Response noCsrf = client(guest).postWithoutCsrf(
            "/api/v1/worries/" + worry.id() + "/activities", "{\"helpType\":\"ACTION\"}");
        assertThat(noCsrf.status()).isEqualTo(403);
        assertThat(noCsrf.code()).isEqualTo("CSRF_REJECTED");
        assertThat(activityCount(worry)).isZero();
    }

    @Test
    void 다른_게스트의_걱정에는_시작할_수_없다() {
        TestGuest owner = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(owner);

        Response response = start(client(fixtures.guest()), worry, "ACTION");

        assertNotFound(response);
        assertThat(activityCount(worry)).isZero();
    }

    @Test
    void 다른_게스트의_활동은_모든_경로에서_404이고_상태가_바뀌지_않는다() {
        TestGuest owner = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(owner);
        Client ownerClient = client(owner);
        UUID action = startedId(ownerClient, worry, "ACTION");
        Client other = client(fixtures.guest());

        for (Function<UUID, Response> call : activityCalls(other)) {
            assertNotFound(call.apply(action));
        }
        Response current = ownerClient.activity(action);
        assertThat(current.state()).isEqualTo("IN_PROGRESS");
        assertThat(current.body().get("version").asInt()).isZero();
    }

    @Test
    void 없는_활동은_404() {
        Client client = client(fixtures.guest());
        for (Function<UUID, Response> call : activityCalls(client)) {
            assertNotFound(call.apply(UUID.randomUUID()));
        }
    }

    @Test
    void 잠긴_걱정에는_409_WORRY_LOCKED이고_공개_시각이_지나면_시작할_수_있다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.lockedWorry(guest);
        Client client = client(guest);

        Response locked = start(client, worry, "REST");
        assertThat(locked.status()).isEqualTo(409);
        assertThat(locked.code()).isEqualTo("WORRY_LOCKED");
        assertThat(activityCount(worry)).isZero();

        clock.advance(Duration.ofDays(1).plusMinutes(1));
        assertThat(start(client, worry, "REST").status()).isEqualTo(201);
    }

    @Test
    void 삭제_중인_걱정에는_시작할_수_없다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.deletingWorry(guest);

        assertNotFound(start(client(guest), worry, "ACTION"));
        assertThat(activityCount(worry)).isZero();
    }

    @Test
    void 만료된_걱정에는_시작할_수_없다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.expiredWorry(guest);

        assertNotFound(start(client(guest), worry, "ACTION"));
        assertThat(activityCount(worry)).isZero();
    }

    @Test
    void 활동_중에_걱정_삭제가_시작되면_모든_경로가_404다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID action = startedId(client, worry, "ACTION");

        jdbc.sql("UPDATE worries SET deletion_started_at = now() WHERE id = :id").param("id", worry.id()).update();

        for (Function<UUID, Response> call : activityCalls(client)) {
            assertNotFound(call.apply(action));
        }
        assertNotFound(start(client, worry, "ACTION"));
    }

    @Test
    void 활동_중에_걱정이_만료되면_모든_경로가_404다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        Client client = client(guest);
        UUID rest = startedId(client, worry, "REST");

        // publicWorry는 만료까지 28일 남았다. 서버 시계만 넘긴다(정리 작업 전에도 차단).
        clock.advance(Duration.ofDays(28).plusMinutes(1));

        for (Function<UUID, Response> call : activityCalls(client)) {
            assertNotFound(call.apply(rest));
        }
    }

    @Test
    void 게스트가_삭제_중이면_서비스도_404로_막는다() {
        TestGuest guest = fixtures.guest();
        TestWorry worry = fixtures.publicWorry(guest);
        UUID action = startedId(client(guest), worry, "ACTION");

        jdbc.sql("UPDATE guests SET state = 'DELETING' WHERE id = :id").param("id", guest.id()).update();

        // HTTP로는 인증 필터가 먼저 401을 낸다. 필터를 거치지 않는 경로를 대비해 서비스 검사도 확인한다.
        assertThat(client(guest).activity(action).status()).isEqualTo(401);
        assertThatThrownBy(() -> service.get(guest.id(), action))
            .isInstanceOfSatisfying(ActivityException.class,
                exception -> assertThat(exception.error()).isEqualTo(ApiError.NOT_FOUND));
        assertThatThrownBy(() -> service.start(guest.id(), worry.id(), HelpType.ACTION, false))
            .isInstanceOfSatisfying(ActivityException.class,
                exception -> assertThat(exception.error()).isEqualTo(ApiError.NOT_FOUND));
    }

    /** 활동 ID를 받는 모든 경로. */
    List<Function<UUID, Response>> activityCalls(Client client) {
        return List.of(
            client::activity,
            id -> client.step(id, 1, true),
            client::pause,
            client::resume);
    }

    static void assertNotFound(Response response) {
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("NOT_FOUND");
    }
}
