package com.cryptotrading.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * A controller slice gets the shared conventions too (registered for {@code @WebMvcTest}): the
 * security rules, Problem Details and the JSON settings. Services write their controller tests
 * this way, with spring-security-test's {@code jwt()}.
 */
@WebMvcTest(TestApplication.SampleController.class)
class WebMvcSliceTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void securityRulesApplyInTheSlice() {
        MvcTestResult anonymous = mvc.get().uri("/sample/quote").exchange();
        assertThat(anonymous).hasStatus(401).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(anonymous).bodyJson().extractingPath("$.code").isEqualTo("UNAUTHORIZED");

        MvcTestResult user = mvc.get()
                .uri("/api/admin/ping")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .exchange();
        assertThat(user).hasStatus(403);
        assertThat(user).bodyJson().extractingPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void jwtPostProcessorFeedsCurrentUser() {
        MvcTestResult result = mvc.get()
                .uri("/api/me")
                .with(jwt().jwt(token -> token.subject("alice-sub").claim("preferred_username", "alice")))
                .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo("alice-sub");
        assertThat(result).bodyJson().extractingPath("$.username").isEqualTo("alice");
    }

    @Test
    void problemDetailsAndJsonConventionsApplyInTheSlice() {
        MvcTestResult error =
                mvc.get().uri("/sample/business-error").with(jwt()).exchange();
        assertThat(error).hasStatus(409).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(error).bodyJson().extractingPath("$.code").isEqualTo("INSUFFICIENT_PLATFORM_FUNDS");

        MvcTestResult quote = mvc.get().uri("/sample/quote").with(jwt()).exchange();
        assertThat(quote).bodyJson().extractingPath("$.qty").isEqualTo("0.00000001");
    }
}
