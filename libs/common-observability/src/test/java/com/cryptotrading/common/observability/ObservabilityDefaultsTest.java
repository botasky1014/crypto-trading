package com.cryptotrading.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ObservabilityDefaultsTest {

    private final ObservabilityDefaults defaults = new ObservabilityDefaults();

    @Test
    void appliesTheDefaults() {
        StandardEnvironment environment = new StandardEnvironment();
        defaults.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus");
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
    }

    @Test
    void serviceConfigurationWins() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "service", java.util.Map.of("management.endpoints.web.exposure.include", "health")));
        defaults.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health");
    }

    @Test
    void instanceTagFallsBackToLocalhostAndDefaultPort() {
        StandardEnvironment environment = new StandardEnvironment();
        defaults.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.metrics.tags.instance")).isEqualTo("localhost:8080");
    }
}
