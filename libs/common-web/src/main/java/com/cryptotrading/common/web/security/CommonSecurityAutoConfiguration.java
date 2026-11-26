package com.cryptotrading.common.web.security;

import com.cryptotrading.common.web.problem.ProblemDetailsSecurityHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.DispatcherServlet;
import tools.jackson.databind.json.JsonMapper;

/**
 * Default security of every servlet service: a stateless OAuth2 Resource Server that checks the
 * Keycloak JWT again behind the gateway (design.md 8.2). The issuer comes from {@code
 * spring.security.oauth2.resourceserver.jwt.issuer-uri} (default in {@link SecurityDefaults}).
 *
 * <table>
 *   <caption>Rules, first match wins</caption>
 *   <tr><td>{@code /actuator/health/**}, {@code /actuator/info}, {@code /actuator/prometheus},
 *       {@code /v3/api-docs/**}, {@code /error}</td><td>anonymous</td></tr>
 *   <tr><td>{@code /internal/**}</td><td>denied (opened to service accounts in P4)</td></tr>
 *   <tr><td>{@code /api/admin/**}</td><td>role {@code admin}</td></tr>
 *   <tr><td>everything else</td><td>any authenticated user</td></tr>
 * </table>
 *
 * <p>{@code /actuator/prometheus} is anonymous so Prometheus can scrape it without a token; only
 * Prometheus reaches it locally, and NetworkPolicy restricts it in Kubernetes (P9).
 *
 * <p>A service that needs different rules defines its own {@link SecurityFilterChain} bean, and
 * this one backs off.
 */
@AutoConfiguration(
        beforeName = {
            "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
            "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web."
                    + "OAuth2ResourceServerWebSecurityAutoConfiguration"
        })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, SecurityFilterChain.class})
public class CommonSecurityAutoConfiguration {

    static final String[] ANONYMOUS_PATHS = {
        "/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/v3/api-docs/**", "/error"
    };

    @Bean
    @ConditionalOnMissingBean
    KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        return new KeycloakJwtAuthenticationConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemDetailsSecurityHandler problemDetailsSecurityHandler(JsonMapper jsonMapper) {
        return new ProblemDetailsSecurityHandler(jsonMapper);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain cryptoTradingSecurityFilterChain(
            HttpSecurity http,
            KeycloakJwtAuthenticationConverter jwtAuthenticationConverter,
            ProblemDetailsSecurityHandler problemHandler) {
        return http.authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        .requestMatchers(ANONYMOUS_PATHS)
                        .permitAll()
                        .requestMatchers("/internal/**")
                        .denyAll()
                        .requestMatchers("/api/admin/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                                .authenticationEntryPoint(problemHandler)
                                .accessDeniedHandler(problemHandler))
                .exceptionHandling(
                        ex -> ex.authenticationEntryPoint(problemHandler).accessDeniedHandler(problemHandler))
                // Bearer tokens only: no session, no cookies, so CSRF protection does not apply.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
