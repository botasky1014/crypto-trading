package com.cryptotrading.common.mybatis.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * Columns every business table has (design.md 6.1): the four audit columns, filled by {@link
 * com.cryptotrading.common.mybatis.fill.AuditFieldsMetaObjectHandler}, and the optimistic lock
 * {@code version}.
 *
 * <pre>{@code
 * created_at TIMESTAMPTZ  NOT NULL,
 * created_by VARCHAR(64)  NOT NULL,   -- Keycloak sub, or system:<application>
 * updated_at TIMESTAMPTZ  NOT NULL,
 * updated_by VARCHAR(64)  NOT NULL,
 * version    BIGINT       NOT NULL DEFAULT 0
 * }</pre>
 *
 * <p>Fields are nullable because a new entity has none of them until it is inserted.
 *
 * <p>Auto-fill only works for writes that pass an entity ({@code insert}, {@code updateById},
 * {@code update(entity, wrapper)}). An update built only from a wrapper ({@code
 * update(null, wrapper)} or a hand-written SQL statement) must set {@code updated_at} / {@code
 * updated_by} itself.
 */
@Getter
@Setter
public abstract class BaseEntity {

    @TableField(fill = FieldFill.INSERT)
    private @Nullable Instant createdAt;

    @TableField(fill = FieldFill.INSERT)
    private @Nullable String createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private @Nullable Instant updatedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private @Nullable String updatedBy;

    /**
     * Optimistic lock: {@code updateById} adds {@code WHERE version = ?} and increments it, and
     * returns 0 updated rows when another writer was first. Starts at 0 on insert.
     */
    @Version
    @TableField(fill = FieldFill.INSERT)
    private @Nullable Long version;
}
