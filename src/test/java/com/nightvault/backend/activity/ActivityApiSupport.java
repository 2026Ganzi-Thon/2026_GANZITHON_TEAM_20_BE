package com.nightvault.backend.activity;

import java.time.Duration;
import java.util.UUID;

import com.nightvault.backend.support.IntegrationTest;
import com.nightvault.backend.support.WorryFixtures;
import com.nightvault.backend.support.WorryFixtures.TestGuest;
import com.nightvault.backend.support.WorryFixtures.TestWorry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * 활동 API 통합 테스트 공통 부모. 실제 보안 필터 체인(게스트 쿠키 인증·CSRF)을 거쳐 호출한다.
 * 컨텍스트 설정을 늘리지 않으려고 {@code @AutoConfigureMockMvc} 대신 MockMvc를 직접 만든다.
 */
abstract class ActivityApiSupport extends IntegrationTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JsonMapper json;

    @Autowired
    ActivityService service;

    MockMvc mvc;

    @BeforeEach
    void setUpMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
            .build();
    }

    /** 게스트 세션 쿠키 + CSRF 토큰을 갖춘 클라이언트. */
    Client client(TestGuest guest) {
        try {
            var result = mvc.perform(MockMvcRequestBuilders.get("/api/v1/csrf").cookie(cookie(guest))).andReturn();
            JsonNode body = json.readTree(result.getResponse().getContentAsString());
            return new Client(guest, (MockHttpSession) result.getRequest().getSession(false),
                body.get("headerName").asString(), body.get("token").asString());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    // ---- 자주 쓰는 호출 ----

    Response start(Client client, TestWorry worry, String helpType) {
        return client.post("/api/v1/worries/" + worry.id() + "/activities", "{\"helpType\":\"" + helpType + "\"}");
    }

    Response replace(Client client, TestWorry worry, String helpType) {
        return client.post("/api/v1/worries/" + worry.id() + "/activities",
            "{\"helpType\":\"" + helpType + "\",\"replaceOpen\":true}");
    }

    UUID startedId(Client client, TestWorry worry, String helpType) {
        Response response = start(client, worry, helpType);
        if (response.status() != 201) {
            throw new AssertionError("시작 실패: " + response);
        }
        return response.activityId();
    }

    /** 바로 완료할 수 있게 만든 뒤 완료한 활동. 휴식은 시계를 180초 넘긴다. */
    UUID completedId(Client client, TestWorry worry, String helpType) {
        UUID id = startedId(client, worry, helpType);
        if (helpType.equals("ACTION")) {
            for (int stepNo = 1; stepNo <= 3; stepNo++) {
                client.step(id, stepNo, true);
            }
        } else if (helpType.equals("REST")) {
            clock.advance(Duration.ofSeconds(180));
        }
        Response completed = client.complete(id);
        if (completed.status() != 200) {
            throw new AssertionError("완료 실패: " + completed);
        }
        return id;
    }

    long activityCount(TestWorry worry) {
        return jdbc.sql("SELECT count(*) FROM activities WHERE worry_id = :id").param("id", worry.id())
            .query(Long.class).single();
    }

    static Cookie cookie(TestGuest guest) {
        return new Cookie(WorryFixtures.SESSION_COOKIE, guest.sessionToken());
    }

    // ---- 값 타입 ----

    final class Client {
        private final TestGuest guest;
        private final MockHttpSession session;
        private final String csrfHeader;
        private final String csrfToken;

        Client(TestGuest guest, MockHttpSession session, String csrfHeader, String csrfToken) {
            this.guest = guest;
            this.session = session;
            this.csrfHeader = csrfHeader;
            this.csrfToken = csrfToken;
        }

        TestGuest guest() {
            return guest;
        }

        Response get(String path) {
            return send(MockMvcRequestBuilders.get(path));
        }

        Response post(String path, String body) {
            return send(withBody(MockMvcRequestBuilders.post(path), body));
        }

        Response post(String path) {
            return send(MockMvcRequestBuilders.post(path));
        }

        Response put(String path, String body) {
            return send(withBody(MockMvcRequestBuilders.put(path), body));
        }

        /** CSRF 토큰 없이 보낸다(403 확인용). */
        Response postWithoutCsrf(String path, String body) {
            try {
                return Response.of(json, mvc.perform(MockMvcRequestBuilders.post(path).cookie(cookie(guest)).session(session)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse());
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        // 활동 경로 단축
        Response activity(UUID id) {
            return get("/api/v1/activities/" + id);
        }

        Response step(UUID id, int stepNo, boolean done) {
            return put("/api/v1/activities/" + id + "/steps/" + stepNo, "{\"done\":" + done + "}");
        }

        Response pause(UUID id) {
            return post("/api/v1/activities/" + id + "/pause");
        }

        Response resume(UUID id) {
            return post("/api/v1/activities/" + id + "/resume");
        }

        Response stop(UUID id) {
            return post("/api/v1/activities/" + id + "/stop");
        }

        Response complete(UUID id) {
            return post("/api/v1/activities/" + id + "/complete");
        }

        Response feedback(UUID id, String answer) {
            return put("/api/v1/activities/" + id + "/feedback", "{\"answer\":\"" + answer + "\"}");
        }

        Response current() {
            return get("/api/v1/activities/current");
        }

        private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder builder, String body) {
            return builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }

        private Response send(MockHttpServletRequestBuilder builder) {
            try {
                return Response.of(json, mvc.perform(builder.cookie(cookie(guest)).session(session)
                    .header(csrfHeader, csrfToken)).andReturn().getResponse());
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    record Response(int status, JsonNode body, String location, String cacheControl) {
        static Response of(JsonMapper json, MockHttpServletResponse response) throws Exception {
            String content = response.getContentAsString();
            return new Response(response.getStatus(), content.isEmpty() ? null : json.readTree(content),
                response.getHeader("Location"), response.getHeader("Cache-Control"));
        }

        String code() {
            return body.get("code").asString();
        }

        UUID activityId() {
            return UUID.fromString(body.get("activityId").asString());
        }

        String state() {
            return body.get("state").asString();
        }
    }
}
