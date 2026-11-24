package com.cryptotrading.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class JacksonConventionsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, CommonWebAutoConfiguration.class));

    record Amounts(BigDecimal tiny, BigDecimal large, BigDecimal trailingZeros, BigDecimal negative) {}

    record Times(Instant instant, OffsetDateTime offsetDateTime) {}

    record Order(BigDecimal qty) {}

    @Test
    void bigDecimalIsWrittenAsPlainString() {
        runner.run(ctx -> {
            JsonMapper mapper = ctx.getBean(JsonMapper.class);

            String json = mapper.writeValueAsString(new Amounts(
                    new BigDecimal("1E-8"),
                    new BigDecimal("1.5E+12"),
                    new BigDecimal("0.10"),
                    new BigDecimal("-3.25")));

            assertThat(json)
                    .isEqualTo(
                            "{\"tiny\":\"0.00000001\",\"large\":\"1500000000000\",\"trailingZeros\":\"0.10\",\"negative\":\"-3.25\"}");
        });
    }

    @Test
    void bigDecimalAcceptsStringsAndNumbers() {
        runner.run(ctx -> {
            JsonMapper mapper = ctx.getBean(JsonMapper.class);

            assertThat(mapper.readValue("{\"qty\":\"0.0001\"}", Order.class).qty())
                    .isEqualTo("0.0001");
            assertThat(mapper.readValue("{\"qty\":0.0001}", Order.class).qty()).isEqualTo("0.0001");
        });
    }

    @Test
    void untypedFloatsAreReadAsBigDecimal() {
        runner.run(ctx -> {
            JsonMapper mapper = ctx.getBean(JsonMapper.class);

            Map<?, ?> map = mapper.readValue("{\"price\":0.1}", Map.class);

            assertThat(map.get("price")).isEqualTo(new BigDecimal("0.1"));
        });
    }

    @Test
    void timesAreIso8601InUtc() {
        runner.run(ctx -> {
            JsonMapper mapper = ctx.getBean(JsonMapper.class);

            String json = mapper.writeValueAsString(new Times(
                    Instant.parse("2026-11-23T08:00:00Z"), OffsetDateTime.parse("2026-11-23T16:00:00+08:00")));

            assertThat(json)
                    .isEqualTo("{\"instant\":\"2026-11-23T08:00:00Z\",\"offsetDateTime\":\"2026-11-23T08:00:00Z\"}");
        });
    }

    @Test
    void serviceSpringJacksonPropertiesStillWin() {
        runner.withPropertyValues("spring.jackson.time-zone=Asia/Shanghai").run(ctx -> {
            JsonMapper mapper = ctx.getBean(JsonMapper.class);

            String json = mapper.writeValueAsString(
                    new Times(Instant.parse("2026-11-23T08:00:00Z"), OffsetDateTime.parse("2026-11-23T08:00:00Z")));

            // Instant is always written in UTC; offset types follow the configured time zone.
            assertThat(json)
                    .isEqualTo(
                            "{\"instant\":\"2026-11-23T08:00:00Z\",\"offsetDateTime\":\"2026-11-23T16:00:00+08:00\"}");
        });
    }
}
