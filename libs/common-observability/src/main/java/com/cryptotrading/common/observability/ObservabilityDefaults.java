package com.cryptotrading.common.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Default observability settings shared by every service (design.md 9.2). Added with the lowest
 * precedence, so any value in application.yml, Nacos, environment variables or command-line
 * arguments wins.
 */
public class ObservabilityDefaults implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "commonObservabilityDefaults";

    static final Map<String, Object> DEFAULTS = defaults();

    private static Map<String, Object> defaults() {
        Map<String, Object> map = new LinkedHashMap<>();
        // Endpoints scraped or probed by the platform; everything else stays hidden.
        map.put("management.endpoints.web.exposure.include", "health,info,prometheus");
        // liveness / readiness groups (/actuator/health/liveness, /actuator/health/readiness)
        // outside Kubernetes too, so Compose and k3d use the same probes.
        map.put("management.endpoint.health.probes.enabled", "true");
        // Common tags on every metric. `instance` is <host>:<port>, where <host> is what Spring Cloud
        // resolves as the local address (an IP locally, the same one the service registers in Nacos);
        // Prometheus keeps it because the scrape job uses honor_labels. Revisited for Pods in P9.
        map.put("management.metrics.tags.application", "${spring.application.name}");
        map.put("management.metrics.tags.instance", "${spring.cloud.client.hostname:localhost}:${server.port:8080}");
        // Histogram buckets for HTTP latency, so dashboards can compute P50 / P99 across instances.
        map.put("management.metrics.distribution.percentiles-histogram.http.server.requests", "true");
        // Structured JSON logs in ECS format; service.name comes from spring.application.name.
        map.put("logging.structured.format.console", "ecs");
        return Map.copyOf(map);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, DEFAULTS));
        }
    }
}
