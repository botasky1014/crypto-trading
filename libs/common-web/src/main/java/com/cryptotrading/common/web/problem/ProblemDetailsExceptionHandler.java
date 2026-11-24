package com.cryptotrading.common.web.problem;

import com.cryptotrading.common.core.error.BusinessException;
import com.cryptotrading.common.core.error.CommonErrorCode;
import com.cryptotrading.common.core.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.accept.NotAcceptableApiVersionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception into an RFC 9457 Problem Details response (design.md 7.1):
 *
 * <pre>{@code
 * {
 *   "title": "Insufficient platform funds",   // English
 *   "status": 409,
 *   "detail": "...",                          // English, no secrets
 *   "instance": "/api/...",
 *   "code": "INSUFFICIENT_PLATFORM_FUNDS",    // stable code, translated by the frontend
 *   "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
 * }
 * }</pre>
 *
 * <p>{@code type} is omitted, which RFC 9457 defines as {@code about:blank}. Validation errors also carry {@code errors: [{field, code}]}, where {@code code} is the
 * constraint name (e.g. {@code NotBlank}). Unexpected exceptions become {@code INTERNAL_ERROR}
 * with a generic detail; the stack trace is only logged.
 *
 * <p>Lowest precedence, so a service may add its own {@code @RestControllerAdvice} for specific
 * exceptions.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ProblemDetailsExceptionHandler extends ResponseEntityExceptionHandler {

    public static final String CODE = "code";
    public static final String TRACE_ID = "traceId";
    public static final String ERRORS = "errors";

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public @Nullable ResponseEntity<Object> handleBusinessException(BusinessException ex, WebRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        HttpStatusCode status = HttpStatusCode.valueOf(errorCode.httpStatus());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        body.setTitle(errorCode.title());
        body.setProperty(CODE, errorCode.code());
        ex.getProperties().forEach(body::setProperty);
        if (status.is5xxServerError()) {
            log.error("Business error {} [traceId={}]", errorCode.code(), traceId(request), ex);
        } else {
            log.debug("Business error {}: {}", errorCode.code(), ex.getMessage());
        }
        return handleExceptionInternal(ex, body, new HttpHeaders(), status, request);
    }

    @ExceptionHandler(Exception.class)
    public @Nullable ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        CommonErrorCode errorCode = CommonErrorCode.INTERNAL_ERROR;
        ProblemDetail body =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        body.setTitle(errorCode.title());
        body.setProperty(CODE, errorCode.code());
        String traceId = traceId(request);
        body.setProperty(TRACE_ID, traceId);
        log.error("Unhandled exception [traceId={}]", traceId, ex);
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.getBody();
        body.setDetail("Request validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> Map.of(
                        "field",
                        error instanceof FieldError fe ? fe.getField() : error.getObjectName(),
                        "code",
                        String.valueOf(error.getCode())))
                .toList();
        body.setProperty(ERRORS, errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * Single exit point: every Spring MVC exception handled by the base class, and the handlers
     * above, pass through here. Adds {@code code}, {@code traceId} and {@code instance}.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body == null) {
            body = ProblemDetail.forStatus(statusCode);
        }
        if (body instanceof ProblemDetail problem) {
            Map<String, Object> properties = problem.getProperties();
            if (properties == null || !properties.containsKey(CODE)) {
                problem.setProperty(CODE, codeFor(ex, statusCode).code());
            }
            if (properties == null || !properties.containsKey(TRACE_ID)) {
                problem.setProperty(TRACE_ID, traceId(request));
            }
            HttpServletRequest servletRequest = servletRequest(request);
            if (problem.getInstance() == null && servletRequest != null) {
                problem.setInstance(URI.create(servletRequest.getRequestURI()));
            }
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static ErrorCode codeFor(Exception ex, HttpStatusCode status) {
        if (ex instanceof MethodArgumentNotValidException || ex instanceof HandlerMethodValidationException) {
            return CommonErrorCode.VALIDATION_FAILED;
        }
        if (ex instanceof InvalidApiVersionException || ex instanceof NotAcceptableApiVersionException) {
            return CommonErrorCode.UNSUPPORTED_API_VERSION;
        }
        return CommonErrorCode.fromHttpStatus(status.value());
    }

    private static String traceId(WebRequest request) {
        return TraceIds.resolve(servletRequest(request));
    }

    private static @Nullable HttpServletRequest servletRequest(WebRequest request) {
        return request instanceof NativeWebRequest nwr ? nwr.getNativeRequest(HttpServletRequest.class) : null;
    }
}
