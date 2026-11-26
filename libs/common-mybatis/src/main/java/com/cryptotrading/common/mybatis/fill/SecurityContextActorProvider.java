package com.cryptotrading.common.mybatis.fill;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Actor from the Spring Security context: the principal name, which the shared JWT converter sets
 * to the token's {@code sub} (common-web {@code KeycloakJwtAuthenticationConverter}). Without an
 * authenticated, non-anonymous user it returns the system actor.
 */
public class SecurityContextActorProvider implements ActorProvider {

    private final String systemActor;

    public SecurityContextActorProvider(String systemActor) {
        this.systemActor = systemActor;
    }

    @Override
    public String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return systemActor;
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? systemActor : name;
    }
}
