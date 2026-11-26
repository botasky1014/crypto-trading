package com.cryptotrading.common.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.cryptotrading.common.mybatis.fill.ActorProvider;
import com.cryptotrading.common.mybatis.fill.AuditFieldsMetaObjectHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CommonMybatisAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonMybatisAutoConfiguration.class));

    @Test
    void registersThePluginsInTheRecommendedOrder() {
        runner.run(context -> {
            MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
            assertThat(interceptor.getInterceptors())
                    .extracting(InnerInterceptor::getClass)
                    .containsExactly(
                            PaginationInnerInterceptor.class,
                            OptimisticLockerInnerInterceptor.class,
                            BlockAttackInnerInterceptor.class);
            assertThat(context.getBean(MetaObjectHandler.class)).isInstanceOf(AuditFieldsMetaObjectHandler.class);
        });
    }

    @Test
    void systemActorIsNamedAfterTheApplication() {
        runner.withPropertyValues("spring.application.name=ledger")
                .run(context -> assertThat(context.getBean(ActorProvider.class).currentActor())
                        .isEqualTo("system:ledger"));
    }

    @Test
    void serviceCanReplaceTheActorProvider() {
        runner.withBean(ActorProvider.class, () -> () -> "system:ledger-reconcile")
                .run(context -> assertThat(context.getBean(ActorProvider.class).currentActor())
                        .isEqualTo("system:ledger-reconcile"));
    }
}
