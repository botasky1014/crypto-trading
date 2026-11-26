package com.cryptotrading.common.web.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Turns a Keycloak access token into an authentication (design.md 8.2):
 *
 * <ul>
 *   <li>realm roles ({@code realm_access.roles}) become {@code ROLE_<NAME>}, e.g. {@code user} →
 *       {@code ROLE_USER}, {@code admin} → {@code ROLE_ADMIN} ({@code -} becomes {@code _});
 *   <li>scopes ({@code scope}) become {@code SCOPE_<scope>} as in Spring's default, for the
 *       service-account client scopes such as {@code internal.ledger} (P4);
 *   <li>the principal name is {@code sub}, the user ID everywhere in the system.
 * </ul>
 */
public final class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String REALM_ACCESS_CLAIM = "realm_access";
    static final String ROLES = "roles";
    static final String ROLE_PREFIX = "ROLE_";

    private final JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();

    public KeycloakJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        delegate.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>(realmRoles(jwt));
            authorities.addAll(scopes.convert(jwt));
            return authorities;
        });
        delegate.setPrincipalClaimName("sub");
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return delegate.convert(jwt);
    }

    /** {@code ROLE_*} authorities from {@code realm_access.roles}; empty when the claim is missing. */
    public static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        if (!(jwt.getClaims().get(REALM_ACCESS_CLAIM) instanceof Map<?, ?> realmAccess)
                || !(realmAccess.get(ROLES) instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(role -> !role.isBlank())
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(
                        ROLE_PREFIX + role.toUpperCase(Locale.ROOT).replace('-', '_')))
                .distinct()
                .toList();
    }
}
