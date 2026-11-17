package com.nightvault.backend.worry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.nightvault.backend.support.ApiTestSupport;
import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import org.junit.jupiter.api.Test;

/** 관련 과거 경험 (PAST-01·02). 픽스처의 AI 요약은 모두 "테스트용 요약입니다."라서 서로 관련 있는 것으로 계산된다. */
class PastExperienceTest extends ApiTestSupport {

    @Test
    void 수정본이_있으면_AI_요약_대신_수정본으로_관련성을_계산한다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);
        fixtures.publicWorry(guest); // AI 요약만 있는 기록은 현재 수정본과 겹치는 단어가 없다.
        TestWorry edited = fixtures.publicWorry(guest);
        editSummary(client, current, "내일 발표 준비가 부족할까 걱정된다");
        editSummary(client, edited, "회의 발표 준비가 부족할까 불안했다");

        Response response = past(client, current);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("worryId").asString()).isEqualTo(edited.id().toString());
        assertThat(response.body().get("summary").asString()).isEqualTo("회의 발표 준비가 부족할까 불안했다");
    }

    @Test
    void 후보의_요약을_관련_없게_수정하면_숨긴다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);
        TestWorry candidate = fixtures.publicWorry(guest);
        assertThat(past(client, current).status()).isEqualTo(200);

        editSummary(client, candidate, "주말 여행 일정이 걱정됐다");

        Response response = past(client, current);
        assertThat(response.status()).isEqualTo(204);
        assertThat(response.cacheControl()).contains("no-store");
    }

    @Test
    void 활동을_고르지_않은_기록도_후보가_되고_도움_유형은_null이다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);
        TestWorry candidate = fixtures.publicWorry(guest);

        Response response = past(client, current);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().get("worryId").asString()).isEqualTo(candidate.id().toString());
        assertThat(response.body().get("helpType").isNull()).isTrue();
        assertThat(response.body().get("helpful").asBoolean()).isFalse();
    }

    @Test
    void 도움_됨_평가가_있으면_helpful이_true다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);
        TestWorry candidate = fixtures.publicWorry(guest);
        UUID activity = UUID.fromString(client.post("/api/v1/worries/" + candidate.id() + "/activities",
            "{\"helpType\":\"EMPATHY\"}").body().get("activityId").asString());
        assertThat(client.post("/api/v1/activities/" + activity + "/complete").status()).isEqualTo(200);
        assertThat(client.put("/api/v1/activities/" + activity + "/feedback", "{\"answer\":\"HELPFUL\"}").status())
            .isEqualTo(200);

        Response response = past(client, current);

        assertThat(response.body().get("helpType").asString()).isEqualTo("EMPATHY");
        assertThat(response.body().get("helpful").asBoolean()).isTrue();
    }

    @Test
    void 이전_활동에_도움_됨이_있으면_최근_활동과_관계없이_그_활동의_유형을_준다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);
        TestWorry candidate = fixtures.publicWorry(guest);
        rated(client, candidate, "EMPATHY", "HELPFUL");
        clock.advance(java.time.Duration.ofMinutes(1));
        rated(client, candidate, "EMPATHY", "NOT_HELPFUL");
        clock.advance(java.time.Duration.ofMinutes(1));
        client.post("/api/v1/worries/" + candidate.id() + "/activities", "{\"helpType\":\"ACTION\"}");

        Response response = past(client, current);

        assertThat(response.body().get("helpful").asBoolean()).isTrue();
        assertThat(response.body().get("helpType").asString()).isEqualTo("EMPATHY");
    }

    @Test
    void 도움_됨_평가가_없으면_helpful은_false고_최근_활동_유형을_준다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);
        TestWorry candidate = fixtures.publicWorry(guest);
        rated(client, candidate, "EMPATHY", "UNSURE");
        clock.advance(java.time.Duration.ofMinutes(1));
        client.post("/api/v1/worries/" + candidate.id() + "/activities", "{\"helpType\":\"ACTION\"}");

        Response response = past(client, current);

        assertThat(response.body().get("helpful").asBoolean()).isFalse();
        assertThat(response.body().get("helpType").asString()).isEqualTo("ACTION");
    }

    /** 활동을 시작·완료하고 평가한다. */
    private void rated(Client client, TestWorry worry, String helpType, String answer) {
        UUID activity = UUID.fromString(client.post("/api/v1/worries/" + worry.id() + "/activities",
            "{\"helpType\":\"" + helpType + "\"}").body().get("activityId").asString());
        assertThat(client.post("/api/v1/activities/" + activity + "/complete").status()).isEqualTo(200);
        assertThat(client.put("/api/v1/activities/" + activity + "/feedback", "{\"answer\":\"" + answer + "\"}").status())
            .isEqualTo(200);
    }

    @Test
    void 잠김_삭제_중_만료_다른_게스트의_기록은_후보가_아니다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        TestWorry current = fixtures.publicWorry(guest);
        fixtures.lockedWorry(guest);
        fixtures.deletingWorry(guest);
        fixtures.expiredWorry(guest);
        fixtures.publicWorry(fixtures.guest());

        assertThat(past(client, current).status()).isEqualTo(204);
    }

    @Test
    void 현재_걱정이_다른_게스트_잠김_삭제_중_만료면_404다() {
        TestGuest guest = fixtures.guest();
        Client client = client(guest);
        fixtures.publicWorry(guest);

        for (TestWorry current : new TestWorry[] {
            fixtures.publicWorry(fixtures.guest()), fixtures.lockedWorry(guest),
            fixtures.deletingWorry(guest), fixtures.expiredWorry(guest)}) {
            Response response = past(client, current);
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.code()).isEqualTo("NOT_FOUND");
        }
    }

    private Response past(Client client, TestWorry current) {
        return client.get("/api/v1/worries/" + current.id() + "/past-experience");
    }

    private static void editSummary(Client client, TestWorry worry, String summary) {
        Response response = client.put("/api/v1/worries/" + worry.id() + "/summary", "{\"summary\":\"" + summary + "\"}");
        assertThat(response.status()).isEqualTo(204);
    }
}
