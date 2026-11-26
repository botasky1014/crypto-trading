package com.cryptotrading.common.web.security;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Default security settings shared by every service. Added with the lowest precedence, so any value
 * in application.yml, Nacos, environment variables or command-line arguments wins.
 *
 * <p>The issuer is fixed to the local Keycloak realm (deploy/keycloak/README.md); it is overridden
 * for Kubernetes in P9. The JWT decoder fetches the keys lazily on the first token, so a service
 * starts even while Keycloak is still starting.
 */
public class SecurityDefaults implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "commonSecurityDefaults";

    static final String ISSUER_URI = "http://localhost:8180/realms/crypto-trading";

    static final Map<String, Object> DEFAULTS =
            Map.of("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER_URI);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, DEFAULTS));
        }
    }
}
