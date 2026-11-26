package com.cryptotrading.common.mybatis.fill;

import static org.assertj.core.api.Assertions.assertThat;

import com.cryptotrading.common.mybatis.entity.BaseEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class AuditFieldsMetaObjectHandlerTest {

    static final String SYSTEM_ACTOR = "system:account";
    static final String USER_ID = "8d3c5a4e-0b7f-4c1e-9a8b-2f6d1e0c3b4a";
    static final Instant NOW = Instant.parse("2026-11-26T08:00:00.123456789Z");
    static final Instant NOW_MICROS = Instant.parse("2026-11-26T08:00:00.123456Z");

    static class SampleEntity extends BaseEntity {}

    private final AuditFieldsMetaObjectHandler handler = new AuditFieldsMetaObjectHandler(
            new SecurityContextActorProvider(SYSTEM_ACTOR), Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String userId) {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of()));
    }

    @Test
    void insertByUserFillsAllAuditFieldsWithTheUserId() {
        signIn(USER_ID);
        SampleEntity entity = new SampleEntity();

        handler.insertFill(SystemMetaObject.forObject(entity));

        assertThat(entity.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(entity.getUpdatedBy()).isEqualTo(USER_ID);
        assertThat(entity.getCreatedAt()).isEqualTo(NOW_MICROS);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW_MICROS);
        assertThat(entity.getVersion()).isZero();
    }

    @Test
    void insertBySystemTaskUsesTheSystemActor() {
        SampleEntity entity = new SampleEntity();

        handler.insertFill(SystemMetaObject.forObject(entity));

        assertThat(entity.getCreatedBy()).isEqualTo(SYSTEM_ACTOR);
        assertThat(entity.getUpdatedBy()).isEqualTo(SYSTEM_ACTOR);
        assertThat(entity.getCreatedAt()).isEqualTo(NOW_MICROS);
    }

    @Test
    void anonymousUserCountsAsSystem() {
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        SampleEntity entity = new SampleEntity();

        handler.insertFill(SystemMetaObject.forObject(entity));

        assertThat(entity.getCreatedBy()).isEqualTo(SYSTEM_ACTOR);
    }

    @Test
    void updateByUserChangesOnlyTheUpdateFields() {
        SampleEntity loaded = new SampleEntity();
        loaded.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        loaded.setCreatedBy(SYSTEM_ACTOR);
        loaded.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        loaded.setUpdatedBy(SYSTEM_ACTOR);
        loaded.setVersion(3L);
        signIn(USER_ID);

        handler.updateFill(SystemMetaObject.forObject(loaded));

        assertThat(loaded.getUpdatedBy()).isEqualTo(USER_ID);
        assertThat(loaded.getUpdatedAt()).isEqualTo(NOW_MICROS);
        assertThat(loaded.getCreatedBy()).isEqualTo(SYSTEM_ACTOR);
        assertThat(loaded.getCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(loaded.getVersion()).isEqualTo(3L);
    }

    @Test
    void updateBySystemTaskUsesTheSystemActor() {
        SampleEntity loaded = new SampleEntity();
        loaded.setUpdatedBy(USER_ID);

        handler.updateFill(SystemMetaObject.forObject(loaded));

        assertThat(loaded.getUpdatedBy()).isEqualTo(SYSTEM_ACTOR);
    }

    @Test
    void callerCannotForgeTheAuditFields() {
        signIn(USER_ID);
        SampleEntity entity = new SampleEntity();
        entity.setCreatedBy("someone-else");
        entity.setCreatedAt(Instant.EPOCH);

        handler.insertFill(SystemMetaObject.forObject(entity));

        assertThat(entity.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(entity.getCreatedAt()).isEqualTo(NOW_MICROS);
    }

    @Test
    void presetVersionIsKeptOnInsert() {
        SampleEntity entity = new SampleEntity();
        entity.setVersion(7L);

        handler.insertFill(SystemMetaObject.forObject(entity));

        assertThat(entity.getVersion()).isEqualTo(7L);
    }
}
