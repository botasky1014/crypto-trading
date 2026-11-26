package com.cryptotrading.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptotrading.common.web.version.ApiVersioning;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class ApiVersioningTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void headerSelectsTheVersion() {
        assertThat(mvc.get().uri("/sample/version").header(ApiVersioning.HEADER, "2"))
                .hasStatusOk()
                .hasBodyTextEqualTo("v2");
        assertThat(mvc.get().uri("/sample/version").header(ApiVersioning.HEADER, "1"))
                .hasStatusOk()
                .hasBodyTextEqualTo("v1");
    }

    @Test
    void missingHeaderUsesTheDefaultVersion() {
        assertThat(mvc.get().uri("/sample/version")).hasStatusOk().hasBodyTextEqualTo("v1");
    }

    @Test
    void unversionedEndpointsServeEveryVersion() {
        assertThat(mvc.get().uri("/sample/quote").header(ApiVersioning.HEADER, "2"))
                .hasStatusOk();
    }

    @Test
    void unknownVersionIsRejected() {
        MvcTestResult result = mvc.get()
                .uri("/sample/version")
                .header(ApiVersioning.HEADER, "99")
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("UNSUPPORTED_API_VERSION");
    }
}
