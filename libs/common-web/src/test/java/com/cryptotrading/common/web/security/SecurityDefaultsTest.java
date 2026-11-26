package com.cryptotrading.common.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class SecurityDefaultsTest {

    private static final String ISSUER = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

    @Test
    void issuerDefaultsToTheLocalRealm() {
        StandardEnvironment environment = new StandardEnvironment();

        new SecurityDefaults().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(ISSUER)).isEqualTo("http://localhost:8180/realms/crypto-trading");
    }

    @Test
    void serviceConfigurationWins() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("service", Map.of(ISSUER, "http://kc/realms/x")));

        new SecurityDefaults().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(ISSUER)).isEqualTo("http://kc/realms/x");
    }
}
