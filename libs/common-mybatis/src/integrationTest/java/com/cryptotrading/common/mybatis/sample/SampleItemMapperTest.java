package com.cryptotrading.common.mybatis.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Mapper slice test against real PostgreSQL (design.md Q7): {@code @MybatisPlusTest} from {@code
 * mybatis-plus-spring-boot4-starter-test}, Testcontainers with the image of Compose {@code core},
 * schema through Flyway. Each test runs in a transaction that is rolled back.
 */
@MybatisPlusTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SampleItemMapperTest {

    static final String USER_ID = "8d3c5a4e-0b7f-4c1e-9a8b-2f6d1e0c3b4a";
    static final String OTHER_USER_ID = "1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b";
    static final String SYSTEM_ACTOR = "system:mybatis-it";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("timescale/timescaledb:2.32.1-pg18").asCompatibleSubstituteFor("postgres"));

    @Autowired
    SampleItemMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String userId) {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of()));
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap(
                "SELECT created_by, updated_by, created_at, updated_at, version FROM sample_item WHERE id = ?", id);
    }

    @Test
    void insertByUserStoresTheUserIdAndTime() {
        signIn(USER_ID);
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        SampleItem item = SampleItem.of("btc", "0.00000001");

        assertThat(mapper.insert(item)).isEqualTo(1);

        assertThat(item.getId()).isNotNull();
        assertThat(item.getVersion()).isZero();
        Map<String, Object> row = row(item.getId());
        assertThat(row).containsEntry("created_by", USER_ID).containsEntry("updated_by", USER_ID);
        assertThat(row.get("version")).isEqualTo(0L);
        SampleItem loaded = mapper.selectById(item.getId());
        // TIMESTAMPTZ round trip keeps the instant, to the microsecond.
        assertThat(loaded.getCreatedAt()).isEqualTo(item.getCreatedAt());
        assertThat(loaded.getCreatedAt()).isBetween(before, Instant.now());
        assertThat(loaded.getQuantity()).isEqualByComparingTo("0.00000001");
    }

    @Test
    void insertBySystemTaskStoresTheSystemActor() {
        SampleItem item = SampleItem.of("eth", "1");

        mapper.insert(item);

        assertThat(row(item.getId()))
                .containsEntry("created_by", SYSTEM_ACTOR)
                .containsEntry("updated_by", SYSTEM_ACTOR);
    }

    @Test
    void updateByIdFillsUpdateFieldsAndBumpsTheVersion() throws InterruptedException {
        signIn(USER_ID);
        SampleItem item = SampleItem.of("sol", "2");
        mapper.insert(item);
        Instant createdAt = item.getCreatedAt();
        Thread.sleep(Duration.ofMillis(5));

        signIn(OTHER_USER_ID);
        SampleItem loaded = mapper.selectById(item.getId());
        loaded.setQuantity(loaded.getQuantity().add(BigDecimal.ONE));
        assertThat(mapper.updateById(loaded)).isEqualTo(1);

        SampleItem reloaded = mapper.selectById(item.getId());
        assertThat(reloaded.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reloaded.getUpdatedBy()).isEqualTo(OTHER_USER_ID);
        assertThat(reloaded.getUpdatedAt()).isAfter(createdAt);
        assertThat(reloaded.getVersion()).isEqualTo(1L);
        assertThat(reloaded.getQuantity()).isEqualByComparingTo("3");
    }

    @Test
    void updateWithEntityAndWrapperIsFilledToo() {
        SampleItem item = SampleItem.of("ltc", "1");
        mapper.insert(item);

        signIn(USER_ID);
        SampleItem change = new SampleItem();
        change.setName("ltc-renamed");
        mapper.update(change, Wrappers.<SampleItem>lambdaUpdate().eq(SampleItem::getId, item.getId()));

        assertThat(row(item.getId())).containsEntry("created_by", SYSTEM_ACTOR).containsEntry("updated_by", USER_ID);
    }

    @Test
    void staleVersionUpdatesNothing() {
        SampleItem item = SampleItem.of("doge", "10");
        mapper.insert(item);
        SampleItem first = mapper.selectById(item.getId());
        // What a concurrent writer holds: same row, version 0. (A second selectById here would return
        // the same cached object, because MyBatis caches per session, i.e. per transaction.)
        SampleItem second = SampleItem.of("doge", "12");
        second.setId(item.getId());
        second.setVersion(first.getVersion());

        first.setQuantity(new BigDecimal("11"));
        assertThat(mapper.updateById(first)).isEqualTo(1);
        assertThat(mapper.updateById(second)).isZero();

        assertThat(mapper.selectById(item.getId()).getQuantity()).isEqualByComparingTo("11");
    }

    @Test
    void paginationCountsAndLimits() {
        for (int i = 0; i < 25; i++) {
            mapper.insert(SampleItem.of("item-%02d".formatted(i), String.valueOf(i)));
        }

        Page<SampleItem> page = mapper.selectPage(
                new Page<>(2, 10), Wrappers.<SampleItem>lambdaQuery().orderByAsc(SampleItem::getName));

        assertThat(page.getTotal()).isEqualTo(25);
        assertThat(page.getPages()).isEqualTo(3);
        assertThat(page.getRecords()).extracting(SampleItem::getName).first().isEqualTo("item-10");
        assertThat(page.getRecords()).hasSize(10);
    }

    @Test
    void fullTableUpdateAndDeleteAreRejected() {
        mapper.insert(SampleItem.of("btc", "1"));

        assertThatThrownBy(() -> mapper.delete(Wrappers.emptyWrapper()))
                .hasStackTraceContaining("Prohibition of full table deletion");
        SampleItem change = new SampleItem();
        change.setName("all");
        assertThatThrownBy(() -> mapper.update(change, Wrappers.emptyWrapper()))
                .hasStackTraceContaining("Prohibition of table update operation");

        assertThat(mapper.selectCount(null)).isEqualTo(1);
    }
}
