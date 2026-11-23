package com.cryptotrading.common.core.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Base class for expected business failures. The web layer turns it into a Problem Details
 * response with the {@link ErrorCode}'s status and code.
 *
 * <p>The message becomes the Problem Details {@code detail}: English only, and never containing
 * secrets. Extra machine-readable values the frontend may need for its translated text (e.g. a
 * limit) go into {@link #withProperty properties}, which are added to the response as fields.
 */
public class BusinessException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient Map<String, Object> properties = new LinkedHashMap<>();

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, Objects.requireNonNull(errorCode, "errorCode").title());
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public BusinessException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        if (!ErrorCode.isValidCode(errorCode.code())) {
            throw new IllegalArgumentException("Error code must be UPPER_SNAKE_CASE: " + errorCode.code());
        }
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** Adds a field to the Problem Details response. Returns this for chaining. */
    public BusinessException withProperty(String name, Object value) {
        properties.put(Objects.requireNonNull(name, "name"), value);
        return this;
    }

    public Map<String, Object> getProperties() {
        return Collections.unmodifiableMap(properties);
    }
}
