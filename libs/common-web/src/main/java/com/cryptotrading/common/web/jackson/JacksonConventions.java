package com.cryptotrading.common.web.jackson;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.TimeZone;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON conventions of the public API (design.md 7.1).
 *
 * <ul>
 *   <li>{@link BigDecimal} is written as a JSON string in plain notation ({@code "0.00000001"},
 *       never {@code 1E-8}), so JavaScript clients do not lose precision. Both strings and numbers
 *       are accepted on input.
 *   <li>Floating-point numbers read into untyped targets ({@code Object}, {@code Map}) become
 *       {@link BigDecimal}, not {@code Double}.
 *   <li>Dates and times are ISO-8601 strings in UTC ({@code 2026-11-23T08:00:00Z}); offsets other
 *       than UTC are converted.
 * </ul>
 */
public final class JacksonConventions {

    private JacksonConventions() {}

    public static JsonMapper.Builder apply(JsonMapper.Builder builder) {
        return builder.withConfigOverride(
                        BigDecimal.class, o -> o.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .enable(DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE)
                .defaultTimeZone(TimeZone.getTimeZone(ZoneOffset.UTC));
    }
}
