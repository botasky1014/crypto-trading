package com.cryptotrading.common.core.error;

/** Error codes shared by all services (generic HTTP and framework errors). */
public enum CommonErrorCode implements ErrorCode {
    BAD_REQUEST(400),
    VALIDATION_FAILED(400),
    UNSUPPORTED_API_VERSION(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    NOT_ACCEPTABLE(406),
    CONFLICT(409),
    PAYLOAD_TOO_LARGE(413),
    UNSUPPORTED_MEDIA_TYPE(415),
    TOO_MANY_REQUESTS(429),
    INTERNAL_ERROR(500),
    SERVICE_UNAVAILABLE(503);

    private final int httpStatus;

    CommonErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * Generic code for an HTTP status raised by the framework (no specific business code).
     * Unknown 4xx statuses map to {@link #BAD_REQUEST}, everything else to {@link #INTERNAL_ERROR}.
     */
    public static CommonErrorCode fromHttpStatus(int status) {
        return switch (status) {
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> TOO_MANY_REQUESTS;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status >= 400 && status < 500 ? BAD_REQUEST : INTERNAL_ERROR;
        };
    }
}
