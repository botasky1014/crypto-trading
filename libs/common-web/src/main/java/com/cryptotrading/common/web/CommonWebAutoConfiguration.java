package com.cryptotrading.common.web;

import com.cryptotrading.common.web.jackson.JacksonConventions;
import com.cryptotrading.common.web.problem.ProblemDetailsExceptionHandler;
import com.cryptotrading.common.web.version.ApiVersioning;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Registers the shared web conventions; a service only needs the {@code common-web} dependency. */
@AutoConfiguration(before = JacksonAutoConfiguration.class)
public class CommonWebAutoConfiguration {

    /**
     * Runs before Boot's own customizer (order 0), so {@code spring.jackson.*} properties set by a
     * service still win.
     */
    @Bean
    @Order(-100)
    JsonMapperBuilderCustomizer cryptoTradingJsonConventions() {
        return JacksonConventions::apply;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(DispatcherServlet.class)
    static class ServletWebConfiguration {

        @Bean
        @ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)
        ProblemDetailsExceptionHandler problemDetailsExceptionHandler() {
            return new ProblemDetailsExceptionHandler();
        }

        @Bean
        ApiVersioning apiVersioning() {
            return new ApiVersioning();
        }
    }
}
