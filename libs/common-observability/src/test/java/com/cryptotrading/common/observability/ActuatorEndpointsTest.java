package com.cryptotrading.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest(properties = {"spring.application.name=sample", "server.port=8123"})
@AutoConfigureMockMvc
// Tests normally use an in-memory registry; this keeps the Prometheus registry and endpoint.
@AutoConfigureMetrics
class ActuatorEndpointsTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void exposesOnlyHealthInfoAndPrometheus() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/info")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/env")).hasStatus(404);
        assertThat(mvc.get().uri("/actuator/metrics")).hasStatus(404);
    }

    @Test
    void hasLivenessAndReadinessGroups() {
        assertThat(mvc.get().uri("/actuator/health/liveness"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
        assertThat(mvc.get().uri("/actuator/health/readiness"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void prometheusMetricsCarryTheCommonTagsAndHttpHistogram() throws Exception {
        assertThat(mvc.get().uri("/sample/ping")).hasStatusOk();

        String body = mvc.get().uri("/actuator/prometheus").exchange().getResponse().getContentAsString();
        assertThat(body)
                .contains("jvm_memory_used_bytes{")
                .contains("application=\"sample\"")
                .contains("instance=\"localhost:8123\"")
                .contains("http_server_requests_seconds_bucket{");
    }
}
