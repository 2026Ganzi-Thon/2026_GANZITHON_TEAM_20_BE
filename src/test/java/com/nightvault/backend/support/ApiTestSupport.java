package com.nightvault.backend.support;

import com.nightvault.backend.support.WorryFixtures.TestGuest;
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
 * 금고·기록·회고·삭제 API 통합 테스트 공통 부모. 실제 보안 필터 체인(게스트 쿠키 인증·CSRF)을 거쳐 호출한다.
 * 컨텍스트 설정을 늘리지 않으려고 {@code @AutoConfigureMockMvc} 대신 MockMvc를 직접 만든다.
 */
public abstract class ApiTestSupport extends IntegrationTest {

    @Autowired
    protected WebApplicationContext context;

    @Autowired
    protected JsonMapper json;

    protected MockMvc mvc;

    @BeforeEach
    void setUpApiMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
            .build();
    }

    /** 게스트 세션 쿠키 + CSRF 토큰을 갖춘 클라이언트. */
    protected Client client(TestGuest guest) {
        try {
            var result = mvc.perform(MockMvcRequestBuilders.get("/api/v1/csrf").cookie(cookie(guest))).andReturn();
            JsonNode body = json.readTree(result.getResponse().getContentAsString());
            return new Client(guest, (MockHttpSession) result.getRequest().getSession(false),
                body.get("headerName").asString(), body.get("token").asString());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    protected static Cookie cookie(TestGuest guest) {
        return new Cookie(WorryFixtures.SESSION_COOKIE, guest.sessionToken());
    }

    protected final class Client {
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

        public TestGuest guest() {
            return guest;
        }

        public Response get(String path) {
            return send(MockMvcRequestBuilders.get(path));
        }

        public Response post(String path) {
            return send(MockMvcRequestBuilders.post(path));
        }

        public Response post(String path, String body) {
            return send(withBody(MockMvcRequestBuilders.post(path), body));
        }

        public Response put(String path, String body) {
            return send(withBody(MockMvcRequestBuilders.put(path), body));
        }

        public Response patch(String path, String body) {
            return send(withBody(MockMvcRequestBuilders.patch(path), body));
        }

        public Response delete(String path) {
            return send(MockMvcRequestBuilders.delete(path));
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

    public record Response(int status, JsonNode body, String cacheControl) {
        static Response of(JsonMapper json, MockHttpServletResponse response) throws Exception {
            String content = response.getContentAsString();
            return new Response(response.getStatus(), content.isEmpty() ? null : json.readTree(content),
                response.getHeader("Cache-Control"));
        }

        public String code() {
            return body.get("code").asString();
        }
    }
}
