package com.cryptotrading.common.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cryptotrading.common.core.error.BusinessException;
import com.cryptotrading.common.core.error.CommonErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class CurrentUserTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsSubjectUsernameAndEmail() {
        Jwt jwt = KeycloakJwtAuthenticationConverterTest.token()
                .claim("preferred_username", "alice")
                .claim("email", "alice@example.com")
                .build();

        assertThat(CurrentUser.from(jwt)).isEqualTo(new CurrentUser("8d3c5a4e-user-sub", "alice", "alice@example.com"));
    }

    @Test
    void emailIsOptional() {
        Jwt jwt = KeycloakJwtAuthenticationConverterTest.token()
                .claim("preferred_username", "alice")
                .build();

        assertThat(CurrentUser.from(jwt).email()).isNull();
    }

    @Test
    void comesFromTheSecurityContext() {
        Jwt jwt = KeycloakJwtAuthenticationConverterTest.token()
                .claim("preferred_username", "alice")
                .build();
        SecurityContextHolder.getContext().setAuthentication(converter.convert(jwt));

        assertThat(CurrentUser.get().id()).isEqualTo("8d3c5a4e-user-sub");
        assertThat(CurrentUser.find()).map(CurrentUser::username).contains("alice");
    }

    @Test
    void missingOutsideAnAuthenticatedRequest() {
        assertThat(CurrentUser.find()).isEmpty();
        assertThatThrownBy(CurrentUser::get)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHORIZED));
    }

    @Test
    void ignoresNonJwtAuthentication() {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated("bob", null, java.util.List.of()));

        assertThat(CurrentUser.find()).isEmpty();
    }

    @Test
    void tokenWithoutSubjectIsRejected() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("azp", "web")
                .build();

        assertThatThrownBy(() -> CurrentUser.from(jwt)).isInstanceOf(BusinessException.class);
    }
}
