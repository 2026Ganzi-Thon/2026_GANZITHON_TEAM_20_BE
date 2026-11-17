package com.nightvault.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.nightvault.backend.support.IntegrationTest;
import com.nightvault.backend.support.WorryFixtures;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

class FrontendHttpContractTest extends IntegrationTest {
    @Autowired
    WebApplicationContext context;

    @Autowired
    JsonMapper json;

    MockMvc mvc;

    @BeforeEach
    void configureMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void unsupportedMethodKeepsTheStatusAndAllowHeaderWithJsonError() throws Exception {
        var response = authenticated(HttpMethod.PUT, "/api/v1/guest-sessions/current",
            MediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON, "{}");
        assertError(response, 405, "METHOD_NOT_ALLOWED");
        assertThat(response.getHeader("Allow")).contains("GET");
    }

    @Test
    void unsupportedContentTypeReturnsAnUncachedJsonError() throws Exception {
        var response = authenticated(HttpMethod.POST, "/api/v1/worry-requests",
            MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, "synthetic input");
        assertError(response, 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unsupportedAcceptReturnsAnUncachedJsonError() throws Exception {
        var response = authenticated(HttpMethod.GET, "/api/v1/guest-sessions/current",
            MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON, "");
        assertError(response, 406, "NOT_ACCEPTABLE");
    }

    @Test
    void unknownRouteReturnsThePublicNotFoundContract() throws Exception {
        var response = authenticated(HttpMethod.GET, "/api/v1/not-a-real-resource",
            MediaType.APPLICATION_JSON, MediaType.APPLICATION_JSON, "");
        assertError(response, 404, "NOT_FOUND");
    }

    @Test
    void csrfMustBeReloadedAfterGuestCreation() throws Exception {
        var fetched = mvc.perform(get("/api/v1/csrf")).andReturn();
        var session = (MockHttpSession) fetched.getRequest().getSession(false);
        var oldToken = json.readTree(fetched.getResponse().getContentAsString()).get("token").asString();
        var login = mvc.perform(post("/api/v1/guest-sessions").session(session)
            .header("X-CSRF-TOKEN", oldToken).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mode\":\"START\"}")).andReturn().getResponse();
        assertThat(login.getStatus()).isEqualTo(200);
        var cookie = new Cookie(WorryFixtures.SESSION_COOKIE,
            login.getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1]);

        var stale = mvc.perform(post("/api/v1/guest-sessions").session(session).cookie(cookie)
            .header("X-CSRF-TOKEN", oldToken).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mode\":\"START\"}")).andReturn().getResponse();
        assertError(stale, 403, "CSRF_REJECTED");

        var refreshed = mvc.perform(get("/api/v1/csrf").session(session).cookie(cookie)).andReturn().getResponse();
        var newToken = json.readTree(refreshed.getContentAsString()).get("token").asString();
        var retried = mvc.perform(post("/api/v1/guest-sessions").session(session).cookie(cookie)
            .header("X-CSRF-TOKEN", newToken).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mode\":\"START\"}")).andReturn().getResponse();
        assertThat(retried.getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse authenticated(HttpMethod method, String path, MediaType accept,
                                                  MediaType contentType, String body) throws Exception {
        var guest = fixtures.guest();
        var cookie = new Cookie(WorryFixtures.SESSION_COOKIE, guest.sessionToken());
        var fetched = mvc.perform(get("/api/v1/csrf").cookie(cookie)).andReturn();
        var csrf = json.readTree(fetched.getResponse().getContentAsString());
        return mvc.perform(request(method, path).cookie(cookie)
            .session((MockHttpSession) fetched.getRequest().getSession(false))
            .header(csrf.get("headerName").asString(), csrf.get("token").asString())
            .accept(accept).contentType(contentType).content(body)).andReturn().getResponse();
    }

    private void assertError(MockHttpServletResponse response, int status, String code) {
        assertThat(response.getStatus()).isEqualTo(status);
        var body = json.readTree(response.getContentAsByteArray());
        assertThat(body.has("code")).isTrue();
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("message").asString()).isNotBlank();
        assertThat(body.size()).isEqualTo(2);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }
}
