package com.cryptotrading.common.mybatis;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.cryptotrading.common.mybatis.fill.ActorProvider;
import com.cryptotrading.common.mybatis.fill.AuditFieldsMetaObjectHandler;
import com.cryptotrading.common.mybatis.fill.SecurityContextActorProvider;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * MyBatis-Plus conventions of every service (design.md 6.1). Runs before MyBatis-Plus's own
 * auto-configuration, which picks up the interceptor and the fill handler.
 *
 * <p>Also registered for {@code @MybatisPlusTest}, so mapper slice tests behave like the service.
 */
@AutoConfiguration(before = MybatisPlusAutoConfiguration.class)
@ConditionalOnClass(MybatisPlusInterceptor.class)
public class CommonMybatisAutoConfiguration {

    /**
     * Plugin order as recommended by MyBatis-Plus: pagination, optimistic lock, then the guard
     * that rejects {@code UPDATE} / {@code DELETE} without a {@code WHERE} clause.
     */
    @Bean
    @ConditionalOnMissingBean
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }

    @Bean
    @ConditionalOnMissingBean
    MetaObjectHandler auditFieldsMetaObjectHandler(ActorProvider actorProvider, ObjectProvider<Clock> clock) {
        return new AuditFieldsMetaObjectHandler(actorProvider, clock.getIfAvailable(Clock::systemUTC));
    }

    static String systemActor(Environment environment) {
        return ActorProvider.SYSTEM_PREFIX + environment.getProperty("spring.application.name", "unknown");
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    static class SecurityActorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ActorProvider actorProvider(Environment environment) {
            return new SecurityContextActorProvider(systemActor(environment));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.springframework.security.core.context.SecurityContextHolder")
    static class SystemActorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ActorProvider actorProvider(Environment environment) {
            String systemActor = systemActor(environment);
            return () -> systemActor;
        }
    }
}
