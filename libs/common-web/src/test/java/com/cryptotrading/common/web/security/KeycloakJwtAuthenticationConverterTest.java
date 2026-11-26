package com.cryptotrading.common.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();

    static Jwt.Builder token() {
        return Jwt.withTokenValue("token").header("alg", "none").subject("8d3c5a4e-user-sub");
    }

    // Spring Security 7 also adds FACTOR_BEARER (the authentication factor); only roles and scopes matter here.
    private static List<String> authorities(AbstractAuthenticationToken authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_") || a.startsWith("SCOPE_"))
                .toList();
    }

    @Test
    void userRoleBecomesRoleUser() {
        Jwt jwt = token().claim(
                        "realm_access",
                        Map.of("roles", List.of("user", "offline_access", "default-roles-crypto-trading")))
                .build();

        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authorities(authentication))
                .contains("ROLE_USER", "ROLE_OFFLINE_ACCESS", "ROLE_DEFAULT_ROLES_CRYPTO_TRADING")
                .doesNotContain("ROLE_ADMIN");
    }

    @Test
    void adminHasRoleAdminAndRoleUser() {
        Jwt jwt = token().claim("realm_access", Map.of("roles", List.of("user", "admin")))
                .build();

        assertThat(authorities(converter.convert(jwt))).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void tokenWithoutRolesHasNoRoleAuthorities() {
        Jwt noClaim = token().claim("scope", "openid").build();
        Jwt noRoles = token().claim("realm_access", Map.of()).build();
        Jwt emptyRoles =
                token().claim("realm_access", Map.of("roles", List.of())).build();
        Jwt wrongType = token().claim("realm_access", "user").build();

        assertThat(authorities(converter.convert(noClaim))).containsExactly("SCOPE_openid");
        assertThat(authorities(converter.convert(noRoles))).isEmpty();
        assertThat(authorities(converter.convert(emptyRoles))).isEmpty();
        assertThat(authorities(converter.convert(wrongType))).isEmpty();
    }

    @Test
    void scopesAreKeptForServiceAccounts() {
        Jwt jwt = token().claim("scope", "profile email internal.ledger").build();

        assertThat(authorities(converter.convert(jwt)))
                .containsExactlyInAnyOrder("SCOPE_profile", "SCOPE_email", "SCOPE_internal.ledger");
    }

    @Test
    void principalNameIsTheSubject() {
        Jwt jwt = token().claim("preferred_username", "alice").build();

        assertThat(converter.convert(jwt).getName()).isEqualTo("8d3c5a4e-user-sub");
    }
}
