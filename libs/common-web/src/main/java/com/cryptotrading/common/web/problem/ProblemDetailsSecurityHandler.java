package com.cryptotrading.common.web.problem;

import com.cryptotrading.common.core.error.CommonErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security failures in the same Problem Details format as every other error: 401 with code
 * {@code UNAUTHORIZED} and 403 with code {@code FORBIDDEN}. They happen in the filter chain, before
 * Spring MVC, so {@link ProblemDetailsExceptionHandler} never sees them.
 *
 * <p>The {@code WWW-Authenticate} header of RFC 6750 is still set by Spring's bearer token
 * handlers; this class only adds the body. Details are generic: why a token was rejected is not
 * told to the caller.
 */
public class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final AuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();
    private final JsonMapper jsonMapper;

    public ProblemDetailsSecurityHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException, ServletException {
        bearerEntryPoint.commence(request, response, authException);
        write(request, response, CommonErrorCode.UNAUTHORIZED, "Authentication is required");
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        bearerAccessDenied.handle(request, response, accessDeniedException);
        write(request, response, CommonErrorCode.FORBIDDEN, "Access is denied");
    }

    private void write(
            HttpServletRequest request, HttpServletResponse response, CommonErrorCode errorCode, String detail)
            throws IOException {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(errorCode.httpStatus()), detail);
        body.setTitle(errorCode.title());
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty(ProblemDetailsExceptionHandler.CODE, errorCode.code());
        body.setProperty(ProblemDetailsExceptionHandler.TRACE_ID, TraceIds.resolve(request));
        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
