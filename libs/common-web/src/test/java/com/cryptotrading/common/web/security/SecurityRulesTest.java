package com.cryptotrading.common.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The default rules end to end: a real Bearer header goes through the filter chain and the
 * Keycloak converter. Only the signature check is replaced by a decoder that knows three tokens.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityRulesTest {

    static final String USER_TOKEN = "user-token";
    static final String ADMIN_TOKEN = "admin-token";
    static final String NO_ROLE_TOKEN = "no-role-token";

    @TestConfiguration
    static class FakeKeycloak {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> switch (token) {
                case USER_TOKEN -> jwt(token, "alice", List.of("user", "offline_access"));
                case ADMIN_TOKEN -> jwt(token, "admin", List.of("user", "admin"));
                case NO_ROLE_TOKEN -> jwt(token, "service", List.of());
                default -> throw new BadJwtException("Invalid token");
            };
        }

        private static Jwt jwt(String token, String username, List<String> roles) {
            return Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .issuer(SecurityDefaults.ISSUER_URI)
                    .subject(username + "-sub")
                    .claim("preferred_username", username)
                    .claim("email", username + "@example.com")
                    .claim("realm_access", Map.of("roles", roles))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
        }
    }

    @Autowired
    MockMvcTester mvc;

    private MvcTestResult get(String path, String token) {
        return mvc.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    @Test
    void requestWithoutTokenIsUnauthorizedProblem() {
        MvcTestResult result = mvc.get().uri("/sample/quote").exchange();

        assertThat(result).hasStatus(401).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("UNAUTHORIZED");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(401);
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/sample/quote");
        assertThat(result).bodyJson().extractingPath("$.traceId").asString().matches("[0-9a-f]{32}");
    }

    @Test
    void invalidTokenIsUnauthorizedProblem() {
        MvcTestResult result = get("/sample/quote", "forged");

        assertThat(result).hasStatus(401).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("UNAUTHORIZED");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
    }

    @Test
    void anyAuthenticatedUserReachesTheApi() {
        assertThat(get("/sample/quote", USER_TOKEN)).hasStatusOk();
        assertThat(get("/sample/quote", NO_ROLE_TOKEN)).hasStatusOk();
    }

    @Test
    void currentUserComesFromTheToken() {
        MvcTestResult result = get("/api/me", USER_TOKEN);

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                        {"id": "alice-sub", "username": "alice", "email": "alice@example.com"}
                        """);
    }

    @Test
    void adminApiNeedsTheAdminRole() {
        MvcTestResult asUser = get("/api/admin/ping", USER_TOKEN);
        assertThat(asUser).hasStatus(403).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(asUser).bodyJson().extractingPath("$.code").isEqualTo("FORBIDDEN");
        assertThat(get("/api/admin/ping", NO_ROLE_TOKEN)).hasStatus(403);

        assertThat(get("/api/admin/ping", ADMIN_TOKEN)).hasStatusOk().hasBodyTextEqualTo("admin");
        assertThat(mvc.get().uri("/api/admin/ping").exchange()).hasStatus(401);
    }

    @Test
    void internalApiIsClosedForEveryone() {
        assertThat(get("/internal/ping", USER_TOKEN)).hasStatus(403);
        assertThat(get("/internal/ping", ADMIN_TOKEN)).hasStatus(403);
        assertThat(mvc.get().uri("/internal/ping").exchange()).hasStatus(401);
    }

    /** Anonymous paths pass the filter chain; there is no Actuator or springdoc here, so they end in 404. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/actuator/health",
                "/actuator/health/readiness",
                "/actuator/info",
                "/actuator/prometheus",
                "/v3/api-docs",
                "/v3/api-docs/swagger-config"
            })
    void platformEndpointsAreAnonymous(String path) {
        MvcTestResult result = mvc.get().uri(path).exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
    }

    /** Spring Security 7 publishes RFC 9728 metadata, referenced by WWW-Authenticate; it is public by design. */
    @Test
    void protectedResourceMetadataIsPublic() {
        MvcTestResult result =
                mvc.get().uri("/.well-known/oauth-protected-resource").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result)
                .bodyJson()
                .extractingPath("$.bearer_methods_supported")
                .asArray()
                .containsExactly("header");
    }

    @Test
    void otherActuatorEndpointsNeedAToken() {
        assertThat(mvc.get().uri("/actuator/env").exchange()).hasStatus(401);
        assertThat(mvc.get().uri("/actuator/metrics").exchange()).hasStatus(401);
    }

    @Test
    void noSessionIsCreated() {
        MvcTestResult result = get("/sample/quote", USER_TOKEN);

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(result.getRequest().getSession(false)).isNull();
    }
}
