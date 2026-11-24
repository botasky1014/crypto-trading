package com.cryptotrading.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
class ProblemDetailsTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    MockMvcTester mvc;

    @Test
    void businessExceptionBecomesProblemDetailsWithCode() {
        MvcTestResult result = mvc.get()
                .uri("/sample/business-error")
                .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .exchange();

        assertThat(result).hasStatus(409).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        // "type" is omitted, which RFC 9457 defines as "about:blank".
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                        {
                          "title": "Insufficient platform funds",
                          "status": 409,
                          "detail": "Platform pool cannot fund 10000 USD",
                          "instance": "/sample/business-error",
                          "code": "INSUFFICIENT_PLATFORM_FUNDS",
                          "asset": "USD",
                          "traceId": "%s"
                        }
                        """.formatted(TRACE_ID));
    }

    @Test
    void unexpectedExceptionHidesInternalsAndHasTraceId() throws Exception {
        MvcTestResult result = mvc.get().uri("/sample/unexpected-error").exchange();

        assertThat(result).hasStatus(500).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INTERNAL_ERROR");
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("An unexpected error occurred");
        assertThat(result).bodyJson().extractingPath("$.traceId").asString().matches("[0-9a-f]{32}");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("hunter2");
    }

    @Test
    void validationErrorsListFieldsAndConstraintCodes() {
        MvcTestResult result = mvc.post()
                .uri("/sample/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbol\": \"\"}")
                .exchange();

        assertThat(result).hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors")
                .asArray()
                .hasSize(2)
                .anySatisfy(e -> assertThat(e.toString()).contains("symbol", "NotBlank"))
                .anySatisfy(e -> assertThat(e.toString()).contains("qty", "NotNull"));
    }

    @Test
    void frameworkErrorsGetGenericCodes() {
        MvcTestResult notFound = mvc.get().uri("/sample/missing").exchange();
        assertThat(notFound).hasStatus(404).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(notFound).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
        assertThat(notFound).bodyJson().extractingPath("$.traceId").isNotNull();

        MvcTestResult unreadable = mvc.post()
                .uri("/sample/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json")
                .exchange();
        assertThat(unreadable).hasStatus(400);
        assertThat(unreadable).bodyJson().extractingPath("$.code").isEqualTo("BAD_REQUEST");

        MvcTestResult wrongMethod = mvc.delete().uri("/sample/quote").exchange();
        assertThat(wrongMethod).hasStatus(405);
        assertThat(wrongMethod).bodyJson().extractingPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void mvcResponsesUseTheJsonConventions() {
        MvcTestResult result = mvc.get().uri("/sample/quote").exchange();

        assertThat(result).bodyJson().isStrictlyEqualTo("""
                        {"symbol": "BTC-USD", "price": "97000.10", "qty": "0.00000001", "time": "2026-11-23T08:00:00Z"}
                        """);
    }
}
