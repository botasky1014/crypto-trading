package com.cryptotrading.common.web.version;

import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API versioning with Spring Framework 7's built-in support (design.md 7.1): the client sends
 * the version in the {@value #HEADER} request header; without it, {@value #DEFAULT_VERSION} is
 * used. Endpoints opt into a version with e.g. {@code @GetMapping(path = "/orders", version =
 * "2")}; mappings without a version serve every version. Unknown versions are rejected with
 * {@code 400 UNSUPPORTED_API_VERSION}.
 */
public class ApiVersioning implements WebMvcConfigurer {

    public static final String HEADER = "API-Version";
    public static final String DEFAULT_VERSION = "1";

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer.useRequestHeader(HEADER).setDefaultVersion(DEFAULT_VERSION);
    }
}
