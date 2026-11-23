package com.cryptotrading.common.web.problem;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Finds the trace id that goes into Problem Details responses and error logs.
 *
 * <p>Order: the MDC key {@code traceId} (set by Micrometer Tracing once observability is wired
 * in), then the W3C {@code traceparent} request header, then a freshly generated id. A generated
 * id is still useful: it is logged together with the error, so a user's report can be matched.
 */
final class TraceIds {

    static final String MDC_KEY = "traceId";
    static final String TRACEPARENT_HEADER = "traceparent";

    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final String INVALID_TRACE_ID = "0".repeat(32);

    private TraceIds() {}

    static String resolve(HttpServletRequest request) {
        String fromMdc = MDC.get(MDC_KEY);
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        String header = request == null ? null : request.getHeader(TRACEPARENT_HEADER);
        if (header != null) {
            Matcher m = TRACEPARENT.matcher(header.trim());
            if (m.matches() && !m.group(1).equals(INVALID_TRACE_ID)) {
                return m.group(1);
            }
        }
        return generate();
    }

    private static String generate() {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
