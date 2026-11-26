package com.cryptotrading.common.web.security;

import com.cryptotrading.common.core.error.BusinessException;
import com.cryptotrading.common.core.error.CommonErrorCode;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The user behind the current request, read from the Keycloak access token (design.md 8.2).
 *
 * <p>{@link #id()} is the token's {@code sub} and is the only user ID a service may use: queries
 * and operations never take a user ID from request parameters.
 *
 * <pre>{@code
 * String userId = CurrentUser.get().id();
 * }</pre>
 *
 * @param id Keycloak {@code sub}
 * @param username {@code preferred_username}; missing only for unusual clients
 * @param email {@code email}; missing when the token has no {@code email} scope
 */
public record CurrentUser(
        String id, @Nullable String username, @Nullable String email) {

    static final String PREFERRED_USERNAME = "preferred_username";
    static final String EMAIL = "email";

    /** The current user, or {@code UNAUTHORIZED} (401) when the request carries no valid token. */
    public static CurrentUser get() {
        return find().orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED, "No authenticated user"));
    }

    /** The current user; empty outside an authenticated request (e.g. in a scheduled job). */
    public static Optional<CurrentUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            return Optional.of(from(token.getToken()));
        }
        return Optional.empty();
    }

    /** Reads the user from a token, e.g. one injected with {@code @AuthenticationPrincipal Jwt}. */
    public static CurrentUser from(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "Access token has no subject");
        }
        return new CurrentUser(subject, jwt.getClaimAsString(PREFERRED_USERNAME), jwt.getClaimAsString(EMAIL));
    }
}
