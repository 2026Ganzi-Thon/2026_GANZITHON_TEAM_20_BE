package com.nightvault.backend.guest;

import com.nightvault.backend.api.ApiError;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class GuestSecurityConfiguration {
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SecurityFilterChain guestSecurity(
        HttpSecurity http,
        GuestSessionService sessions,
        CsrfTokenRepository csrfTokens,
        JsonMapper json
    ) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        var guestEndpoints = new OrRequestMatcher(
            paths.matcher(HttpMethod.GET, "/api/v1/csrf"),
            paths.matcher(HttpMethod.POST, "/api/v1/guest-sessions"),
            paths.matcher(HttpMethod.GET, "/api/v1/guest-sessions/current")
        );

        return http
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(guestEndpoints).permitAll()
                .anyRequest().authenticated())
            .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
            .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, failure) ->
                    ApiError.unauthenticated(GuestSessionCookie.read(request)).write(response, json))
                .accessDeniedHandler((request, response, failure) ->
                    (failure instanceof CsrfException ? ApiError.CSRF_REJECTED : ApiError.FORBIDDEN)
                        .write(response, json)))
            .addFilterBefore(new GuestAuthenticationFilter(guestEndpoints, sessions, json), AnonymousAuthenticationFilter.class)
            .build();
    }

    private static class GuestAuthenticationFilter extends OncePerRequestFilter {
        private final RequestMatcher guestEndpoints;
        private final GuestSessionService sessions;
        private final JsonMapper json;

        GuestAuthenticationFilter(RequestMatcher guestEndpoints, GuestSessionService sessions, JsonMapper json) {
            this.guestEndpoints = guestEndpoints;
            this.sessions = sessions;
            this.json = json;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return guestEndpoints.matches(request);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
            Optional<UUID> guestId;
            try {
                guestId = sessions.authenticate(GuestSessionCookie.read(request));
            } catch (DataAccessException failure) {
                ApiError.TEMPORARILY_UNAVAILABLE.write(response, json);
                return;
            }

            guestId.ifPresent(id -> {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    id, null, List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));
                SecurityContextHolder.setContext(context);
            });
            chain.doFilter(request, response);
        }
    }
}
