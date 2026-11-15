package com.nightvault.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nightvault.backend.support.IntegrationTest;
import com.nightvault.backend.support.WorryFixtures;
import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import com.nightvault.backend.support.WorryFixtures.WorrySpec;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 시연 API(DEMO-01~03) 통합 테스트. 실제 보안 필터 체인(게스트 쿠키·CSRF)을 거친다. */
class DemoApiTest extends IntegrationTest {
    private static final String KEY = "test-only-demo-key";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JsonMapper json;

    MockMvc mvc;

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
            .build();
    }

    // ---- DEMO-01 권한 ----

    @Test
    void 시연_키가_없거나_틀리면_모든_시연_API가_404다() {
        Client demo = client(demoGuest());
        for (String key : new String[] {null, "wrong-key", ""}) {
            assertThat(demo.get("/api/v1/demo/status", key).status()).isEqualTo(404);
            assertThat(demo.post("/api/v1/demo/presets", key, null).status()).isEqualTo(404);
            assertThat(demo.post("/api/v1/demo/reset", key, null).status()).isEqualTo(404);
            assertThat(demo.post("/api/v1/demo/time-machine", key, "{\"preset\":\"PLUS_24H\"}").status()).isEqualTo(404);
            // 본문이 잘못돼도 키 확인이 먼저다.
            assertThat(demo.post("/api/v1/demo/time-machine", key, "not-json").status()).isEqualTo(404);
            assertThat(demo.post("/api/v1/demo/session", key, null).status()).isEqualTo(404);
        }
        assertThat(guestCount()).isEqualTo(1);
    }

    @Test
    void 일반_게스트는_시연_키가_맞아도_404이고_데이터가_바뀌지_않는다() {
        TestGuest regular = fixtures.guest();
        fixtures.lockedWorry(regular);
        var before = snapshot(regular);
        Client client = client(regular);

        assertThat(client.get("/api/v1/demo/status", KEY).status()).isEqualTo(404);
        assertThat(client.post("/api/v1/demo/presets", KEY, null).status()).isEqualTo(404);
        assertThat(client.post("/api/v1/demo/reset", KEY, null).status()).isEqualTo(404);
        assertThat(client.post("/api/v1/demo/time-machine", KEY, "{\"preset\":\"TO_PUBLISH\"}").status()).isEqualTo(404);
        assertThat(snapshot(regular)).isEqualTo(before);
    }

    @Test
    void 세션_없이_시연_API를_부르면_404다() {
        Client anonymous = anonymousClient();
        assertThat(anonymous.get("/api/v1/demo/status", KEY).status()).isEqualTo(404);
        assertThat(anonymous.post("/api/v1/demo/presets", KEY, null).status()).isEqualTo(404);
    }

    @Test
    void 시연_키가_맞으면_시연_게스트를_발급하고_시연_게스트로_표시한다() {
        Client anonymous = anonymousClient();
        Response issued = anonymous.post("/api/v1/demo/session", KEY, null);

        assertThat(issued.status()).isEqualTo(200);
        assertThat(issued.setCookie()).startsWith(WorryFixtures.SESSION_COOKIE + "=");
        assertThat(jdbc.sql("SELECT kind FROM guests").query(String.class).list()).containsExactly("DEMO");
    }

    @Test
    void 시연_세션_발급은_일반_게스트_쿠키를_재사용하지_않는다() {
        TestGuest regular = fixtures.guest();
        Response issued = client(regular).post("/api/v1/demo/session", KEY, null);

        assertThat(issued.status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT kind FROM guests WHERE id = :id").param("id", regular.id())
            .query(String.class).single()).isEqualTo("REGULAR");
        assertThat(jdbc.sql("SELECT count(*) FROM guests WHERE kind = 'DEMO'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void 이미_시연_게스트면_같은_게스트를_유지한다() {
        TestGuest demo = demoGuest();
        assertThat(client(demo).post("/api/v1/demo/session", KEY, null).status()).isEqualTo(200);
        assertThat(guestCount()).isEqualTo(1);
    }

    @Test
    void 시연_상태는_시연_표시와_상태별_개수를_준다() {
        Client demo = client(demoGuest());
        demo.post("/api/v1/demo/presets", KEY, null);

        JsonNode status = demo.get("/api/v1/demo/status", KEY).body();
        assertThat(status.get("demo").asBoolean()).isTrue();
        assertThat(status.get("lockedCount").asLong()).isEqualTo(1);
        assertThat(status.get("publicCount").asLong()).isEqualTo(2);
        assertThat(status.get("failedRequestCount").asLong()).isEqualTo(1);
    }

    // ---- DEMO-03 프리셋 ----

    @Test
    void 프리셋은_상태별_예시를_만들고_예시임을_표시한다() {
        TestGuest guest = demoGuest();
        Client demo = client(guest);

        Response presets = demo.post("/api/v1/demo/presets", KEY, null);

        assertThat(presets.status()).isEqualTo(200);
        assertThat(demo.get("/api/v1/worries?visibility=LOCKED", null).body().get("items")).hasSize(1);
        JsonNode published = demo.get("/api/v1/worries?visibility=PUBLIC", null).body().get("items");
        assertThat(published).hasSize(2);
        for (JsonNode item : published) {
            assertThat(item.get("summary").asString()).startsWith(DemoSamples.MARK);
            JsonNode detail = demo.get("/api/v1/worries/" + item.get("worryId").asString(), null).body();
            assertThat(detail.get("text").asString()).startsWith(DemoSamples.MARK);
            assertThat(detail.get("analysis").get("empathyMessage").asString()).startsWith(DemoSamples.MARK);
        }
        // 분석 실패 카드
        assertThat(jdbc.sql("SELECT count(*) FROM analysis_requests WHERE guest_id = :id AND state = 'FAILED'")
            .param("id", guest.id()).query(Long.class).single()).isEqualTo(1);
        // 회고 대상
        Response candidate = demo.get("/api/v1/reflections/candidate?currentWorryId=" + UUID.randomUUID()
            + "&timeZone=Asia/Seoul", null);
        assertThat(candidate.status()).isEqualTo(200);
    }

    @Test
    void 프리셋을_다시_만들면_같은_구성으로_교체된다() {
        Client demo = client(demoGuest());
        demo.post("/api/v1/demo/presets", KEY, null);
        demo.post("/api/v1/demo/presets", KEY, null);

        JsonNode status = demo.get("/api/v1/demo/status", KEY).body();
        assertThat(status.get("lockedCount").asLong()).isEqualTo(1);
        assertThat(status.get("publicCount").asLong()).isEqualTo(2);
        assertThat(status.get("failedRequestCount").asLong()).isEqualTo(1);
    }

    // ---- DEMO-02 타임머신 ----

    @Test
    void 공개_시각으로_이동하면_잠긴_시연_걱정이_공개되고_일반_게스트는_그대로다() {
        TestGuest regular = fixtures.guest();
        fixtures.lockedWorry(regular);
        fixtures.publicWorry(regular);
        var before = snapshot(regular);

        TestGuest guest = demoGuest();
        Client demo = client(guest);
        demo.post("/api/v1/demo/presets", KEY, null);
        UUID locked = UUID.fromString(demo.get("/api/v1/worries?visibility=LOCKED", null).body()
            .get("items").get(0).get("worryId").asString());
        assertThat(demo.get("/api/v1/worries/" + locked, null).body().get("visibility").asString()).isEqualTo("LOCKED");

        Response moved = demo.post("/api/v1/demo/time-machine", KEY, "{\"preset\":\"TO_PUBLISH\"}");

        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.body().get("shiftedSeconds").asLong()).isBetween(86_399L, 86_400L);
        assertThat(moved.body().get("status").get("lockedCount").asLong()).isZero();
        assertThat(demo.get("/api/v1/worries/" + locked, null).body().get("visibility").asString()).isEqualTo("PUBLIC");
        assertThat(snapshot(regular)).isEqualTo(before);
    }

    @Test
    void 걱정을_지정해_공개_시각으로_이동할_수_있다() {
        TestGuest guest = demoGuest();
        TestWorry soon = fixtures.worry(guest, WorrySpec.of(WorryFixtures.now(), WorryFixtures.now().plus(Duration.ofHours(2))));
        TestWorry later = fixtures.worry(guest, WorrySpec.of(WorryFixtures.now(), WorryFixtures.now().plus(Duration.ofHours(5))));
        Client demo = client(guest);

        Response moved = demo.post("/api/v1/demo/time-machine", KEY,
            "{\"preset\":\"TO_PUBLISH\",\"worryId\":\"" + later.id() + "\"}");

        assertThat(moved.status()).isEqualTo(200);
        assertThat(demo.get("/api/v1/worries/" + soon.id(), null).body().get("visibility").asString()).isEqualTo("PUBLIC");
        assertThat(demo.get("/api/v1/worries/" + later.id(), null).body().get("visibility").asString()).isEqualTo("PUBLIC");
        // 이미 공개된 걱정을 지정하면 409
        assertThat(demo.post("/api/v1/demo/time-machine", KEY,
            "{\"preset\":\"TO_PUBLISH\",\"worryId\":\"" + soon.id() + "\"}").status()).isEqualTo(409);
    }

    @Test
    void 타임머신은_다른_게스트의_걱정을_지정하면_404이고_잠긴_걱정이_없으면_409다() {
        TestGuest regular = fixtures.guest();
        TestWorry other = fixtures.lockedWorry(regular);
        Client demo = client(demoGuest());

        assertThat(demo.post("/api/v1/demo/time-machine", KEY,
            "{\"preset\":\"TO_PUBLISH\",\"worryId\":\"" + other.id() + "\"}").status()).isEqualTo(404);
        Response none = demo.post("/api/v1/demo/time-machine", KEY, "{\"preset\":\"TO_PUBLISH\"}");
        assertThat(none.status()).isEqualTo(409);
        assertThat(none.body().get("code").asString()).isEqualTo("NO_LOCKED_WORRY");
        assertThat(demo.post("/api/v1/demo/time-machine", KEY, "{\"preset\":\"LATER\"}").status()).isEqualTo(400);
        assertThat(demo.post("/api/v1/demo/time-machine", KEY, "{}").status()).isEqualTo(400);
    }

    @Test
    void 이십사시간_이동하면_열람한_공개_걱정이_회고_후보가_된다() {
        TestGuest regular = fixtures.guest();
        fixtures.worry(regular, WorrySpec.of(WorryFixtures.now().minus(Duration.ofHours(3)),
            WorryFixtures.now().minus(Duration.ofHours(1))).withFirstViewedAt(WorryFixtures.now()));
        var before = snapshot(regular);

        TestGuest guest = demoGuest();
        TestWorry worry = fixtures.worry(guest, WorrySpec.of(WorryFixtures.now().minus(Duration.ofHours(3)),
            WorryFixtures.now().minus(Duration.ofHours(1))).withFirstViewedAt(WorryFixtures.now()));
        Client demo = client(guest);
        String candidatePath = "/api/v1/reflections/candidate?currentWorryId=" + UUID.randomUUID() + "&timeZone=Asia/Seoul";
        assertThat(demo.get(candidatePath, null).status()).isEqualTo(204);

        Response moved = demo.post("/api/v1/demo/time-machine", KEY, "{\"preset\":\"PLUS_24H\"}");

        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.body().get("shiftedSeconds").asLong()).isEqualTo(86_400L);
        Response candidate = demo.get(candidatePath, null);
        assertThat(candidate.status()).isEqualTo(200);
        assertThat(candidate.body().get("worryId").asString()).isEqualTo(worry.id().toString());
        // 일반 게스트는 여전히 회고 후보가 없다.
        assertThat(client(regular).get(candidatePath, null).status()).isEqualTo(204);
        assertThat(snapshot(regular)).isEqualTo(before);
    }

    @Test
    void 타임머신은_시각_간격을_유지하고_세션_만료는_옮기지_않는다() {
        TestGuest guest = demoGuest();
        TestWorry worry = fixtures.lockedWorry(guest);
        var before = worryTimes(worry.id());
        var session = sessionExpiry(guest);

        client(guest).post("/api/v1/demo/time-machine", KEY, "{\"preset\":\"PLUS_24H\"}");

        var after = worryTimes(worry.id());
        for (String column : List.of("created_at", "sealed_at", "publish_at", "expires_at")) {
            assertThat(Duration.between(after.get(column), before.get(column))).isEqualTo(Duration.ofHours(24));
        }
        assertThat(sessionExpiry(guest)).isEqualTo(session);
    }

    // ---- DEMO-03 초기화 ----

    @Test
    void 초기화는_시연_게스트_데이터만_지운다() {
        TestGuest regular = fixtures.guest();
        fixtures.publicWorry(regular);
        fixtures.lockedWorry(regular);
        var before = snapshot(regular);

        TestGuest guest = demoGuest();
        Client demo = client(guest);
        demo.post("/api/v1/demo/presets", KEY, null);
        assertThat(count("worries", guest)).isEqualTo(3);

        Response reset = demo.post("/api/v1/demo/reset", KEY, null);

        assertThat(reset.status()).isEqualTo(200);
        assertThat(reset.body().get("publicCount").asLong()).isZero();
        assertThat(count("worries", guest)).isZero();
        assertThat(count("analysis_requests", guest)).isZero();
        // 시연 게스트와 세션은 남아 계속 시연할 수 있다.
        assertThat(demo.get("/api/v1/demo/status", KEY).status()).isEqualTo(200);
        assertThat(snapshot(regular)).isEqualTo(before);
        assertThat(count("worries", regular)).isEqualTo(2);
    }

    // ---- 도우미 ----

    private TestGuest demoGuest() {
        TestGuest guest = fixtures.guest();
        jdbc.sql("UPDATE guests SET kind = 'DEMO' WHERE id = :id").param("id", guest.id()).update();
        return guest;
    }

    private long guestCount() {
        return jdbc.sql("SELECT count(*) FROM guests").query(Long.class).single();
    }

    private long count(String table, TestGuest guest) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE guest_id = :id").param("id", guest.id())
            .query(Long.class).single();
    }

    /** 일반 게스트의 요청·걱정 행 전체(시각 포함). 시연 API 전후 비교용. */
    private List<Map<String, Object>> snapshot(TestGuest guest) {
        var worries = jdbc.sql("""
            SELECT id, created_at, sealed_at, publish_at, first_viewed_at, expires_at, deletion_started_at
            FROM worries WHERE guest_id = :id ORDER BY id
            """).param("id", guest.id()).query().listOfRows();
        var requests = jdbc.sql("""
            SELECT id, state, accepted_at, expires_at, publish_at, next_attempt_at
            FROM analysis_requests WHERE guest_id = :id ORDER BY id
            """).param("id", guest.id()).query().listOfRows();
        var all = new java.util.ArrayList<Map<String, Object>>(worries);
        all.addAll(requests);
        return all;
    }

    private Map<String, java.time.Instant> worryTimes(UUID worryId) {
        return jdbc.sql("SELECT created_at, sealed_at, publish_at, expires_at FROM worries WHERE id = :id")
            .param("id", worryId).query((row, index) -> {
                var times = new java.util.HashMap<String, java.time.Instant>();
                for (String column : List.of("created_at", "sealed_at", "publish_at", "expires_at")) {
                    times.put(column, row.getObject(column, java.time.OffsetDateTime.class).toInstant());
                }
                return times;
            }).single();
    }

    private Object sessionExpiry(TestGuest guest) {
        return jdbc.sql("SELECT expires_at FROM guest_sessions WHERE guest_id = :id").param("id", guest.id())
            .query().singleValue();
    }

    private Client client(TestGuest guest) {
        return newClient(new Cookie(WorryFixtures.SESSION_COOKIE, guest.sessionToken()));
    }

    private Client anonymousClient() {
        return newClient(null);
    }

    private Client newClient(Cookie cookie) {
        try {
            var request = MockMvcRequestBuilders.get("/api/v1/csrf");
            if (cookie != null) {
                request.cookie(cookie);
            }
            var result = mvc.perform(request).andReturn();
            JsonNode body = json.readTree(result.getResponse().getContentAsString());
            return new Client(cookie, (MockHttpSession) result.getRequest().getSession(false),
                body.get("headerName").asString(), body.get("token").asString());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private final class Client {
        private final Cookie cookie;
        private final MockHttpSession session;
        private final String csrfHeader;
        private final String csrfToken;

        Client(Cookie cookie, MockHttpSession session, String csrfHeader, String csrfToken) {
            this.cookie = cookie;
            this.session = session;
            this.csrfHeader = csrfHeader;
            this.csrfToken = csrfToken;
        }

        Response get(String path, String demoKey) {
            return send(MockMvcRequestBuilders.get(path), demoKey);
        }

        Response post(String path, String demoKey, String body) {
            var builder = MockMvcRequestBuilders.post(path);
            if (body != null) {
                builder.contentType(MediaType.APPLICATION_JSON).content(body);
            }
            return send(builder, demoKey);
        }

        private Response send(MockHttpServletRequestBuilder builder, String demoKey) {
            if (cookie != null) {
                builder.cookie(cookie);
            }
            if (demoKey != null) {
                builder.header(DemoAccess.HEADER, demoKey);
            }
            try {
                MockHttpServletResponse response = mvc.perform(builder.session(session).header(csrfHeader, csrfToken))
                    .andReturn().getResponse();
                String content = response.getContentAsString();
                return new Response(response.getStatus(), content.isEmpty() ? null : json.readTree(content),
                    response.getHeader("Set-Cookie"));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private record Response(int status, JsonNode body, String setCookie) {}
}
