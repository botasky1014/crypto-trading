package com.cryptotrading.common.mybatis.fill;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.apache.ibatis.reflection.MetaObject;

/**
 * Fills the audit columns of {@link com.cryptotrading.common.mybatis.entity.BaseEntity} (or any
 * entity with the same {@code @TableField(fill = ...)} fields):
 *
 * <ul>
 *   <li>insert: {@code createdAt}, {@code createdBy}, {@code updatedAt}, {@code updatedBy} (same
 *       time and actor), and {@code version = 0} when it is not set;
 *   <li>update: {@code updatedAt}, {@code updatedBy}.
 * </ul>
 *
 * <p>Values are always overwritten, so a caller cannot forge them, and an entity loaded from the
 * database gets a fresh {@code updatedAt}. (MyBatis-Plus's {@code strictUpdateFill} skips fields
 * that already have a value, which is wrong for loaded entities.) Times are truncated to
 * microseconds, the precision of PostgreSQL's {@code TIMESTAMPTZ}, so the entity holds exactly
 * what is stored.
 */
public class AuditFieldsMetaObjectHandler implements MetaObjectHandler {

    static final String CREATED_AT = "createdAt";
    static final String CREATED_BY = "createdBy";
    static final String UPDATED_AT = "updatedAt";
    static final String UPDATED_BY = "updatedBy";
    static final String VERSION = "version";

    private final ActorProvider actorProvider;
    private final Clock clock;

    public AuditFieldsMetaObjectHandler(ActorProvider actorProvider, Clock clock) {
        this.actorProvider = actorProvider;
        this.clock = clock;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = now();
        String actor = actorProvider.currentActor();
        setFieldValByName(CREATED_AT, now, metaObject);
        setFieldValByName(CREATED_BY, actor, metaObject);
        setFieldValByName(UPDATED_AT, now, metaObject);
        setFieldValByName(UPDATED_BY, actor, metaObject);
        if (getFieldValByName(VERSION, metaObject) == null) {
            setFieldValByName(VERSION, 0L, metaObject);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName(UPDATED_AT, now(), metaObject);
        setFieldValByName(UPDATED_BY, actorProvider.currentActor(), metaObject);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
