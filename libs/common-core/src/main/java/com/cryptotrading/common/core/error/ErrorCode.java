package com.cryptotrading.common.core.error;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A stable, machine-readable error code returned to clients as the {@code code} field of an RFC
 * 9457 Problem Details response (design.md 7.1, 12.4).
 *
 * <p>The backend is not localized: the frontend translates the code (e.g. {@code
 * errors.INSUFFICIENT_PLATFORM_FUNDS}). Codes are therefore part of the API contract: once
 * published, never rename them.
 *
 * <p>Implement this interface with an enum per service; the enum constant name is the code:
 *
 * <pre>{@code
 * public enum LedgerErrorCode implements ErrorCode {
 *     INSUFFICIENT_PLATFORM_FUNDS(409);
 *
 *     private final int httpStatus;
 *
 *     LedgerErrorCode(int httpStatus) { this.httpStatus = httpStatus; }
 *
 *     @Override public int httpStatus() { return httpStatus; }
 * }
 * }</pre>
 */
public interface ErrorCode {

    /** Upper snake case, e.g. {@code INSUFFICIENT_PLATFORM_FUNDS}. */
    Pattern FORMAT = Pattern.compile("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*");

    /** Implemented by enums; the default {@link #code()} uses it. */
    String name();

    /** HTTP status used when this error is returned from a REST endpoint. */
    int httpStatus();

    /** The code sent to clients. Defaults to the enum constant name. */
    default String code() {
        return name();
    }

    /**
     * Short English summary for the Problem Details {@code title}, derived from the code by
     * default: {@code INSUFFICIENT_PLATFORM_FUNDS} becomes {@code Insufficient platform funds}.
     */
    default String title() {
        String words = code().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    static boolean isValidCode(String code) {
        return code != null && FORMAT.matcher(code).matches();
    }
}
